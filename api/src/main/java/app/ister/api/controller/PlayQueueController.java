package app.ister.api.controller;

import app.ister.api.dto.CreatePlayQueueInput;
import app.ister.api.dto.StreamSettingsInput;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.PlayQueuePrefetchService;
import app.ister.core.service.PlayQueueService;
import app.ister.core.status.PlaybackCommandService;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.PlaybackStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Controller
@Slf4j
@RequiredArgsConstructor
public class PlayQueueController {
    private final PlayQueueService playQueueService;

    private final EpisodeRepository episodeRepository;

    private final MovieRepository movieRepository;

    private final TrackRepository trackRepository;

    private final ChapterRepository chapterRepository;

    private final PodcastEpisodeRepository podcastEpisodeRepository;

    private final MediaFileRepository mediaFileRepository;

    private final ImageRepository imageRepository;

    private final PlayQueuePrefetchService playQueuePrefetchService;

    private final PlaybackStatusService playbackStatusService;

    private final PlaybackSessionRegistry playbackSessionRegistry;

    private final PlaybackCommandService playbackCommandService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<PlayQueueEntity> getPlayQueue(@Argument UUID id, Authentication authentication) {
        log.debug("Getting play queue for user: {}, play queue id: {}", authentication.getName(), id);
        return playQueueService.getPlayQueue(id, authentication);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity createPlayQueue(@Argument CreatePlayQueueInput input, Authentication authentication) {
        return playQueueService.createPlayQueue(input.sourceType(), input.sourceId(), input.startId(), Boolean.TRUE.equals(input.shuffle()), authentication);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Optional<PlayQueueEntity> updatePlayQueue(@Argument UUID id, @Argument long progressInMilliseconds, @Argument UUID playQueueItemId,
                                                     @Argument StreamSettingsInput streamSettings, @Argument PlayState playState,
                                                     Authentication authentication) {
        PlayQueueService.StreamSettings settings = streamSettings == null ? null
                : new PlayQueueService.StreamSettings(streamSettings.direct(), streamSettings.transcode(), streamSettings.subtitleFormat());
        Optional<PlayQueueEntity> playQueue;
        try {
            playQueue = playQueueService.updatePlayQueue(id, progressInMilliseconds, playQueueItemId, settings, authentication);
        } catch (DataAccessException | TransactionException ex) {
            // Transient DB trouble (e.g. an exhausted connection pool during a transcode
            // burst) must not silence the now-playing feed: the registry is in-memory, so
            // re-publish the last known session with the fresh progress/state, then
            // surface the error so the client retries the persistent update.
            republishLastKnownSession(id, playQueueItemId, progressInMilliseconds, playState, authentication);
            throw ex;
        }
        playQueue.ifPresent(queue -> {
            playQueuePrefetchService.maybePrefetchNext(queue, playQueueItemId, progressInMilliseconds);
            publishPlaybackHeartbeat(queue, playQueueItemId, progressInMilliseconds, playState);
        });
        return playQueue;
    }

    /**
     * DB-free fallback heartbeat: reuses the session as last seen in the in-memory
     * registry, bumping only progress/state. The registry entry originated from an
     * ownership-checked update and carries that owner's OIDC subject, so ownership is
     * re-verified against the JWT subject without the database (which is exactly what
     * is unavailable here). Skipped when the client moved on to another queue item —
     * the stale media fields would then describe the wrong track.
     */
    private void republishLastKnownSession(UUID playQueueId, UUID playQueueItemId, long progressInMilliseconds,
                                           PlayState playState, Authentication authentication) {
        playbackSessionRegistry.find(playQueueId)
                .filter(last -> authentication.getName() != null
                        && authentication.getName().equals(last.getUserExternalId()))
                .filter(last -> playQueueItemId == null || playQueueItemId.equals(last.getPlayQueueItemId()))
                .ifPresent(last -> playbackStatusService.publishHeartbeat(
                        playQueueId, last.getPlayQueueItemId(), last.getUserId(), last.getUserExternalId(),
                        last.getUserName(), last.getMediaType(), last.getMediaId(), last.getTitle(),
                        last.getDurationInMilliseconds(), last.getArtworkImageId(),
                        progressInMilliseconds, playState));
    }

    /**
     * Feeds the cluster-wide now-playing view. Runs on the request thread (open
     * Hibernate session), so the lazy user/media associations may be navigated here;
     * only plain values go into the heartbeat message.
     */
    private void publishPlaybackHeartbeat(PlayQueueEntity queue, UUID playQueueItemId, long progressInMilliseconds, PlayState playState) {
        Optional<PlayQueueItemEntity> item = Optional.ofNullable(queue.getItems()).orElse(List.of()).stream()
                .filter(candidate -> candidate.getId().equals(playQueueItemId))
                .findFirst();
        playbackStatusService.publishHeartbeat(
                queue.getId(),
                playQueueItemId,
                queue.getUserEntity().getId(),
                queue.getUserEntity().getExternalId(),
                queue.getUserEntity().getName(),
                item.map(PlayQueueItemEntity::getType).orElse(null),
                item.map(PlayQueueController::mediaIdOf).orElse(null),
                item.map(this::titleOf).orElse(null),
                item.map(this::durationOf).orElse(null),
                item.map(this::artworkOf).orElse(null),
                progressInMilliseconds,
                playState);
    }

    /** Duration of the playing item's media file; the longest one wins if there are several. */
    private Long durationOf(PlayQueueItemEntity item) {
        UUID mediaId = mediaIdOf(item);
        if (mediaId == null) {
            return null;
        }
        List<MediaFileEntity> files = switch (item.getType()) {
            case MOVIE -> mediaFileRepository.findByMovieEntityId(mediaId);
            case EPISODE -> mediaFileRepository.findByEpisodeEntityId(mediaId);
            case TRACK -> mediaFileRepository.findByTrackEntityId(mediaId);
            case CHAPTER -> mediaFileRepository.findByChapterEntityId(mediaId);
            case PODCAST_EPISODE -> mediaFileRepository.findByPodcastEpisodeEntityId(mediaId);
            case BOOK, COMIC -> List.of();
        };
        return files.stream()
                .map(MediaFileEntity::getDurationInMilliseconds)
                .filter(duration -> duration > 0)
                .max(Long::compare)
                .orElse(null);
    }

    /**
     * Cover art for the now-playing card: the movie poster, the episode still (show
     * poster when the episode has none), or the album cover for a track. Falls back to
     * any image when there is no COVER. Clients fetch the bytes via GET /images/{id}/download.
     */
    private UUID artworkOf(PlayQueueItemEntity item) {
        List<ImageEntity> images = switch (item.getType()) {
            case MOVIE -> item.getMovieEntityId() == null ? List.of()
                    : imageRepository.findByMovieEntityId(item.getMovieEntityId());
            case EPISODE -> episodeArtwork(item);
            case TRACK -> item.getTrackEntityId() == null ? List.<ImageEntity>of()
                    : trackRepository.findById(item.getTrackEntityId())
                            .map(track -> imageRepository.findByAlbumEntityId(track.getAlbumEntity().getId()))
                            .orElse(List.of());
            case CHAPTER -> item.getChapterEntityId() == null ? List.<ImageEntity>of()
                    : chapterRepository.findById(item.getChapterEntityId())
                            .map(chapter -> imageRepository.findByBookEntityId(chapter.getBookEntity().getId()))
                            .orElse(List.of());
            case PODCAST_EPISODE -> item.getPodcastEpisodeEntityId() == null ? List.<ImageEntity>of()
                    : podcastEpisodeRepository.findById(item.getPodcastEpisodeEntityId())
                            .map(this::podcastEpisodeArtwork)
                            .orElse(List.of());
            case BOOK, COMIC -> List.of();
        };
        return images.stream().filter(image -> image.getType() == ImageType.COVER).findFirst()
                .or(() -> images.stream().findFirst())
                .map(ImageEntity::getId)
                .orElse(null);
    }

    /** Episode image when the feed provided one, otherwise the podcast cover. */
    private List<ImageEntity> podcastEpisodeArtwork(PodcastEpisodeEntity episode) {
        List<ImageEntity> episodeImages = imageRepository.findByPodcastEpisodeEntityId(episode.getId());
        if (!episodeImages.isEmpty()) {
            return episodeImages;
        }
        return imageRepository.findByPodcastEntityId(episode.getPodcastEntity().getId());
    }

    private List<ImageEntity> episodeArtwork(PlayQueueItemEntity item) {
        if (item.getEpisodeEntityId() == null) {
            return List.of();
        }
        List<ImageEntity> stills = imageRepository.findByEpisodeEntityId(item.getEpisodeEntityId());
        if (!stills.isEmpty()) {
            return stills;
        }
        return item.getEpisodeEntity() == null || item.getEpisodeEntity().getShowEntity() == null
                ? List.of()
                : imageRepository.findByShowEntityId(item.getEpisodeEntity().getShowEntity().getId());
    }

    private static UUID mediaIdOf(PlayQueueItemEntity item) {
        return switch (item.getType()) {
            case MOVIE -> item.getMovieEntityId();
            case EPISODE -> item.getEpisodeEntityId();
            case TRACK -> item.getTrackEntityId();
            case CHAPTER -> item.getChapterEntityId();
            case PODCAST_EPISODE -> item.getPodcastEpisodeEntityId();
            case BOOK, COMIC -> null;
        };
    }

    private String titleOf(PlayQueueItemEntity item) {
        return switch (item.getType()) {
            case MOVIE -> item.getMovieEntity() == null ? null : item.getMovieEntity().getName();
            case EPISODE -> item.getEpisodeEntity() == null ? null
                    : "%s S%02dE%02d".formatted(item.getEpisodeEntity().getShowEntity().getName(),
                            item.getEpisodeEntity().getSeasonEntity().getNumber(), item.getEpisodeEntity().getNumber());
            case TRACK -> item.getTrackEntityId() == null ? null
                    : trackRepository.findById(item.getTrackEntityId())
                            .map(track -> track.getMetadataEntities().stream()
                                    .map(MetadataEntity::getTitle)
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElse(track.getAlbumEntity().getName() + " – track " + track.getNumber()))
                            .orElse(null);
            case CHAPTER -> item.getChapterEntityId() == null ? null
                    : chapterRepository.findById(item.getChapterEntityId())
                            .map(chapter -> chapter.getMetadataEntities().stream()
                                    .map(MetadataEntity::getTitle)
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElse(chapter.getBookEntity().getName() + " – chapter " + chapter.getNumber()))
                            .orElse(null);
            case PODCAST_EPISODE -> item.getPodcastEpisodeEntityId() == null ? null
                    : podcastEpisodeRepository.findById(item.getPodcastEpisodeEntityId())
                            .map(episode -> episode.getMetadataEntities().stream()
                                    .map(MetadataEntity::getTitle)
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElse(episode.getPodcastEntity().getTitle()))
                            .orElse(null);
            case BOOK, COMIC -> null;
        };
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity movePlayQueueItem(@Argument UUID playQueueId, @Argument UUID playQueueItemId, @Argument UUID afterPlayQueueItemId, Authentication authentication) {
        PlayQueueEntity queue = playQueueService.movePlayQueueItem(playQueueId, playQueueItemId, afterPlayQueueItemId, authentication);
        playbackCommandService.publishQueueChanged(playQueueId);
        return queue;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity removePlayQueueItem(@Argument UUID playQueueId, @Argument UUID playQueueItemId, Authentication authentication) {
        PlayQueueEntity queue = playQueueService.removePlayQueueItem(playQueueId, playQueueItemId, authentication);
        playbackCommandService.publishQueueChanged(playQueueId);
        return queue;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity addPlayQueueItem(@Argument UUID playQueueId, @Argument MediaType mediaType, @Argument UUID mediaId, @Argument UUID afterPlayQueueItemId, Authentication authentication) {
        PlayQueueEntity queue = playQueueService.addPlayQueueItem(playQueueId, mediaType, mediaId, afterPlayQueueItemId, authentication);
        playbackCommandService.publishQueueChanged(playQueueId);
        return queue;
    }

    @SchemaMapping(typeName = "PlayQueue", field = "currentItemId")
    public UUID currentItemId(PlayQueueEntity playQueueEntity) {
        return playQueueEntity.getCurrentItem();
    }

    // Return only the 10 items that precede the current item, the current item itself, and the 10 items that follow it (max 21 items).
    @SchemaMapping(typeName = "PlayQueue", field = "playQueueItems")
    public List<PlayQueueItemEntity> playQueueItems(PlayQueueEntity playQueueEntity) {
        List<PlayQueueItemEntity> all = playQueueEntity.getItems();

        UUID currentId = playQueueEntity.getCurrentItem();
        if (currentId == null) {
            // no current item – return the first 21 items (or less)
            return all.stream()
                    .limit(21)
                    .toList();
        }

        // locate the index of the current item
        int currentIdx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(currentId)) {
                currentIdx = i;
                break;
            }
        }
        if (currentIdx == -1) {
            // currentItem points to a non‑existent row – behave like “no current”
            return all.stream()
                    .limit(21)
                    .toList();
        }

        int from = Math.max(0, currentIdx - 10);
        int to   = Math.min(all.size(), currentIdx + 11); // exclusive upper bound

        return all.subList(from, to);
    }

    @SchemaMapping(typeName = "PlayQueue", field = "currentItem")
    public Optional<PlayQueueItemEntity> currentItem(PlayQueueEntity playQueueEntity) {
        UUID currentItem = playQueueEntity.getCurrentItem();
        if (currentItem == null) {
            return Optional.empty();
        }
        return playQueueEntity.getItems().stream()
                .filter(item -> item.getId().equals(currentItem))
                .findFirst();
    }

    @SchemaMapping(typeName = "PlayQueueItem", field = "episode")
    public Optional<EpisodeEntity> playQueueItemEpisode(PlayQueueItemEntity playQueueItemEntity) {
        if (playQueueItemEntity.getEpisodeEntityId() != null) {
            return episodeRepository.findById(playQueueItemEntity.getEpisodeEntityId());
        } else return Optional.empty();
    }

    @SchemaMapping(typeName = "PlayQueueItem", field = "movie")
    public Optional<MovieEntity> playQueueItemMovie(PlayQueueItemEntity playQueueItemEntity) {
        if (playQueueItemEntity.getMovieEntityId() != null) {
            return movieRepository.findById(playQueueItemEntity.getMovieEntityId());
        } else return Optional.empty();
    }

    @SchemaMapping(typeName = "PlayQueueItem", field = "track")
    public Optional<TrackEntity> playQueueItemTrack(PlayQueueItemEntity playQueueItemEntity) {
        if (playQueueItemEntity.getTrackEntityId() != null) {
            return trackRepository.findById(playQueueItemEntity.getTrackEntityId());
        } else return Optional.empty();
    }

    @SchemaMapping(typeName = "PlayQueueItem", field = "chapter")
    public Optional<ChapterEntity> playQueueItemChapter(PlayQueueItemEntity playQueueItemEntity) {
        if (playQueueItemEntity.getChapterEntityId() != null) {
            return chapterRepository.findById(playQueueItemEntity.getChapterEntityId());
        } else return Optional.empty();
    }

    @SchemaMapping(typeName = "PlayQueueItem", field = "podcastEpisode")
    public Optional<PodcastEpisodeEntity> playQueueItemPodcastEpisode(PlayQueueItemEntity playQueueItemEntity) {
        if (playQueueItemEntity.getPodcastEpisodeEntityId() != null) {
            return podcastEpisodeRepository.findById(playQueueItemEntity.getPodcastEpisodeEntityId());
        } else return Optional.empty();
    }
}
