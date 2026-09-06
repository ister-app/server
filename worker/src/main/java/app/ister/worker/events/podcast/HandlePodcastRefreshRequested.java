package app.ister.worker.events.podcast;

import app.ister.core.status.ActivityContext;
import app.ister.core.status.ActivitySubjects;
import app.ister.core.Handle;
import app.ister.core.config.OwnDirectoriesProperties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.FileFromPathEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.PodcastEpisodeDownloadRequestedData;
import app.ister.core.eventdata.PodcastRefreshRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ServerEventService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_PODCAST_REFRESH_REQUESTED;

/**
 * Fetches a podcast's RSS feed and syncs it into the database: channel metadata and cover on the
 * podcast, one {@link PodcastEpisodeEntity} per feed item (deduplicated on guid). The newest
 * {@code auto-download-count} episodes get a download request on the cache directory of a node
 * that serves libraries: this node when it owns directories, else (on a helper node, which has
 * none and whose URL clients cannot reach) the node already holding this podcast's downloads,
 * else any node owning a library directory.
 */
@Slf4j
@Service
@Transactional
public class HandlePodcastRefreshRequested implements Handle<PodcastRefreshRequestedData> {
    private static final String FEED_URI_PREFIX = "feed://";
    /** Advisory-lock namespace for {@link PodcastRepository#tryLockPodcast}; distinct from segment detection's. */
    static final int PODCAST_REFRESH_LOCK_NAMESPACE = 0x50444352; // "PDCR"

    private final PodcastRepository podcastRepository;
    private final PodcastEpisodeRepository podcastEpisodeRepository;
    private final MetadataRepository metadataRepository;
    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;
    private final RssFeedParser rssFeedParser;
    private final ImageDownloadService imageDownloadService;
    private final ServerEventService serverEventService;
    private final MessageSender messageSender;
    private final DirectoryRepository directoryRepository;
    private final OwnDirectoriesProperties ownDirectories;

    @Value("${app.ister.server.name}")
    private String nodeName;

    @Value("${app.ister.worker.podcast.auto-download-count:3}")
    private int autoDownloadCount;

    public HandlePodcastRefreshRequested(PodcastRepository podcastRepository,
                                         PodcastEpisodeRepository podcastEpisodeRepository,
                                         MetadataRepository metadataRepository,
                                         ImageRepository imageRepository,
                                         MediaFileRepository mediaFileRepository,
                                         RssFeedParser rssFeedParser,
                                         ImageDownloadService imageDownloadService,
                                         ServerEventService serverEventService,
                                         MessageSender messageSender,
                                         DirectoryRepository directoryRepository,
                                         OwnDirectoriesProperties ownDirectories) {
        this.podcastRepository = podcastRepository;
        this.podcastEpisodeRepository = podcastEpisodeRepository;
        this.metadataRepository = metadataRepository;
        this.imageRepository = imageRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.rssFeedParser = rssFeedParser;
        this.imageDownloadService = imageDownloadService;
        this.serverEventService = serverEventService;
        this.messageSender = messageSender;
        this.directoryRepository = directoryRepository;
        this.ownDirectories = ownDirectories;
    }

    @Override
    public EventType handles() {
        return EventType.PODCAST_REFRESH_REQUESTED;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_PODCAST_REFRESH_REQUESTED)
    @Override
    public void listener(PodcastRefreshRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(PodcastRefreshRequestedData data) {
        if (!podcastRepository.tryLockPodcast(PODCAST_REFRESH_LOCK_NAMESPACE, data.getPodcastId())) {
            // Another node (every node runs the refresh scheduler) is syncing this feed right
            // now; its result is what we would have produced, so drop this one.
            log.debug("Podcast {} is already being refreshed, skipping this message", data.getPodcastId());
            return;
        }
        podcastRepository.findById(data.getPodcastId()).ifPresent(podcast -> {
            ActivityContext.report(ActivitySubjects.describe(podcast, ActivitySubjects.empty()).withTitle(podcast.getTitle()));
            if (!podcast.isActive()) {
                return;
            }
            Optional<RssFeedParser.Feed> fetched =
                    rssFeedParser.fetch(podcast.getFeedUrl(), podcast.getFeedEtag(), podcast.getFeedLastModified());
            if (fetched.isEmpty()) {
                return; // fetch/parse failure: leave lastRefreshedAt so the next sweep retries
            }
            RssFeedParser.Feed feed = fetched.get();
            podcast.setLastRefreshedAt(Instant.now());
            if (feed.notModified()) {
                podcastRepository.save(podcast);
                // Still request the auto-downloads: a download that was missed earlier (e.g.
                // its request raced this handler's commit) would otherwise never recover as
                // long as the feed's etag stays the same.
                requestAutoDownloads(podcast);
                return;
            }
            podcast.setFeedEtag(feed.etag());
            podcast.setFeedLastModified(feed.lastModified());
            syncChannel(podcast, feed.channel());
            podcastRepository.save(podcast);
            syncEpisodes(podcast, feed);
        });
    }

    private void syncChannel(PodcastEntity podcast, RssFeedParser.Channel channel) {
        if (channel == null) {
            return;
        }
        if (channel.title() != null) {
            podcast.setTitle(channel.title());
        }
        if (channel.author() != null) {
            podcast.setAuthor(channel.author());
        }
        String iso3Language = toIso3(channel.language());
        if (iso3Language != null) {
            podcast.setLanguage(iso3Language);
        }

        // Channel description: replace on every refresh (feeds update their about-text).
        metadataRepository.deleteAll(metadataRepository.findByPodcastEntityId(podcast.getId()));
        metadataRepository.save(MetadataEntity.builder()
                .title(channel.title())
                .description(channel.description())
                .language(iso3Language)
                .podcastEntity(podcast)
                .sourceUri(FEED_URI_PREFIX + podcast.getFeedUrl())
                .build());
        serverEventService.createSearchIndexEvent(SearchEntityType.PODCAST, podcast.getId());

        if (channel.imageUrl() != null && imageRepository.findByPodcastEntityId(podcast.getId()).isEmpty()) {
            try {
                imageDownloadService.downloadAndSave(channel.imageUrl(), ImageType.COVER, podcast.getLanguage(),
                        channel.imageUrl(),
                        new ImageSave.MediaEntityRef(null, null, null, null, null, null, podcast));
            } catch (IOException e) {
                log.warn("Could not download podcast cover for {}: {}", podcast.getTitle(), e.getMessage());
            }
        }
    }

    private void syncEpisodes(PodcastEntity podcast, RssFeedParser.Feed feed) {
        for (RssFeedParser.Item item : feed.items()) {
            if (podcastEpisodeRepository.findByPodcastEntityAndGuid(podcast, item.guid()).isPresent()) {
                continue;
            }
            PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder()
                    .podcastEntity(podcast)
                    .guid(item.guid())
                    .publishedAt(item.publishedAt())
                    .enclosureUrl(item.enclosureUrl())
                    .enclosureType(item.enclosureType())
                    .durationHintInMilliseconds(item.durationInMilliseconds())
                    .episodeNumber(item.episodeNumber())
                    .seasonNumber(item.seasonNumber())
                    .build();
            podcastEpisodeRepository.save(episode);
            metadataRepository.save(MetadataEntity.builder()
                    .title(item.title())
                    .description(item.description())
                    .language(podcast.getLanguage())
                    .released(item.publishedAt() == null ? null
                            : item.publishedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate())
                    .podcastEpisodeEntity(episode)
                    .sourceUri(FEED_URI_PREFIX + podcast.getFeedUrl())
                    .build());
            serverEventService.createPodcastEpisodeFoundEvent(episode.getId());
        }
        requestAutoDownloads(podcast);
    }

    /**
     * Newest N episodes of the feed get downloaded up-front; older ones download on demand.
     *
     * <p>Published after commit: this handler is transactional, and the download consumer looks
     * the episode up by id and SKIPS (no retry) when it is missing — so a request consumed
     * before this transaction's episode rows are visible silently loses the download until the
     * next scheduled refresh.
     */
    private void requestAutoDownloads(PodcastEntity podcast) {
        List<UUID> episodeIds = podcastEpisodeRepository
                .findEpisodeIdsForPodcastOrdered(podcast.getId(), autoDownloadCount, 0).stream()
                .filter(episodeId -> !mediaFileRepository.existsByPodcastEpisodeEntityId(episodeId))
                .toList();
        if (episodeIds.isEmpty()) {
            return;
        }
        Runnable publish = () -> episodeIds.forEach(episodeId -> messageSender.sendPodcastEpisodeDownloadRequested(
                PodcastEpisodeDownloadRequestedData.builder()
                        .eventType(EventType.PODCAST_EPISODE_DOWNLOAD_REQUESTED)
                        .podcastEpisodeId(episodeId)
                        .build(),
                downloadCacheDirectoryName(podcast)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    /**
     * Where the audio should land. A node that serves libraries keeps it in its own cache
     * directory (StartupTasks naming). A helper node owns no directories and its URL is not one
     * clients use, so it hands the download to the node that already holds this podcast's
     * episodes, else to any node owning a library directory.
     */
    private String downloadCacheDirectoryName(PodcastEntity podcast) {
        if (!ownDirectories.names().isEmpty()) {
            return nodeName + "-cache-directory";
        }
        return podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(podcast.getId(), 50, 0).stream()
                .flatMap(episodeId -> mediaFileRepository.findByPodcastEpisodeEntityId(episodeId).stream())
                .map(FileFromPathEntity::getDirectoryEntity)
                .filter(dir -> dir != null && dir.getDirectoryType() == DirectoryType.CACHE)
                .map(DirectoryEntity::getName)
                .findFirst()
                .or(() -> directoryRepository.findByDirectoryType(DirectoryType.LIBRARY).stream()
                        .flatMap(lib -> directoryRepository
                                .findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, lib.getNodeEntity()).stream())
                        .map(DirectoryEntity::getName)
                        .findFirst())
                .orElseGet(() -> {
                    log.warn("No node with library directories found; podcast downloads land on helper node {}", nodeName);
                    return nodeName + "-cache-directory";
                });
    }

    private String toIso3(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return null;
        }
        try {
            String iso3 = Locale.forLanguageTag(languageTag.strip()).getISO3Language();
            return iso3.isBlank() ? null : iso3;
        } catch (Exception _) {
            return null;
        }
    }
}
