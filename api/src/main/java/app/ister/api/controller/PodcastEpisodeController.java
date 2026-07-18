package app.ister.api.controller;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.UserPodcastPreferenceEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.SortingOrder;
import app.ister.core.eventdata.PodcastEpisodeDownloadRequestedData;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.UserPodcastPreferenceRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.MessageSender;
import app.ister.core.service.PodcastPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class PodcastEpisodeController {
    private final PodcastEpisodeRepository podcastEpisodeRepository;
    private final PodcastRepository podcastRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final UserPodcastPreferenceRepository userPodcastPreferenceRepository;
    private final PodcastPreferenceService podcastPreferenceService;
    private final MessageSender messageSender;
    private final LibraryAccessService libraryAccessService;

    @Value("${app.ister.server.name}")
    private String nodeName;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<PodcastEpisodeEntity> podcastEpisodeById(@Argument UUID id, Authentication authentication) {
        return podcastEpisodeRepository.findById(id)
                .filter(episode -> libraryAccessService.canAccess(
                        episode.getPodcastEntity().getLibraryEntity(), authentication));
    }

    /**
     * Without an explicit sortingOrder the caller's stored preference for this podcast decides the
     * direction, so every client of that user sees the same order without having to ask for it.
     */
    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<PodcastEpisodeEntity> podcastEpisodes(
            @Argument UUID podcastId,
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingOrder> sortingOrder,
            Authentication authentication) {
        int pageSize = Math.clamp(size.orElse(25), 1, Paging.MAX_PAGE_SIZE);
        SortingOrder order = sortingOrder.orElseGet(
                () -> podcastPreferenceService.getEpisodeOrder(authentication, podcastId));
        Sort sort = Sort.by("publishedAt");
        sort = order == SortingOrder.ASCENDING ? sort.ascending() : sort.descending();
        PageRequest pageRequest = PageRequest.of(Math.max(page.orElse(0), 0), pageSize, sort);
        if (podcastRepository.findById(podcastId)
                .filter(podcast -> libraryAccessService.canAccess(podcast.getLibraryEntity(), authentication))
                .isEmpty()) {
            return Page.empty(pageRequest);
        }
        return podcastEpisodeRepository.findByPodcastEntityId(podcastId, pageRequest);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean setPodcastEpisodeOrder(
            @Argument UUID podcastId, @Argument SortingOrder order, Authentication authentication) {
        podcastPreferenceService.setEpisodeOrder(authentication, podcastId, order);
        return true;
    }

    /**
     * Entities without a preference row map to the default, so the non-null GraphQL field always
     * resolves.
     */
    @BatchMapping(typeName = "Podcast", field = "episodeOrder")
    public Map<PodcastEntity, SortingOrder> episodeOrder(
            List<PodcastEntity> podcasts, Authentication authentication) {
        Map<UUID, SortingOrder> byId = userPodcastPreferenceRepository
                .findByUserEntityExternalIdAndPodcastEntityIn(authentication.getName(), podcasts).stream()
                .collect(Collectors.toMap(p -> p.getPodcastEntity().getId(),
                        UserPodcastPreferenceEntity::getEpisodeOrder));
        return podcasts.stream().collect(Collectors.toMap(
                podcast -> podcast,
                podcast -> byId.getOrDefault(podcast.getId(),
                        PodcastPreferenceService.DEFAULT_EPISODE_ORDER)));
    }

    /** Sends the download to THIS node's cache directory; the client polls `downloaded`. */
    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean downloadPodcastEpisode(@Argument UUID episodeId, Authentication authentication) {
        if (podcastEpisodeRepository.findById(episodeId)
                .filter(episode -> libraryAccessService.canAccess(
                        episode.getPodcastEntity().getLibraryEntity(), authentication))
                .isEmpty()) {
            throw new NoSuchElementException("Podcast episode not found: " + episodeId);
        }
        messageSender.sendPodcastEpisodeDownloadRequested(PodcastEpisodeDownloadRequestedData.builder()
                .eventType(EventType.PODCAST_EPISODE_DOWNLOAD_REQUESTED)
                .podcastEpisodeId(episodeId)
                .build(), nodeName + "-cache-directory");
        return true;
    }

    @SchemaMapping(typeName = "PodcastEpisode", field = "podcast")
    public PodcastEntity podcast(PodcastEpisodeEntity episode) {
        return episode.getPodcastEntity();
    }

    @SchemaMapping(typeName = "PodcastEpisode", field = "metadata")
    public List<MetadataEntity> metadata(PodcastEpisodeEntity episode) {
        return episode.getMetadataEntities();
    }

    @SchemaMapping(typeName = "PodcastEpisode", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(PodcastEpisodeEntity episode) {
        return episode.getMediaFileEntities();
    }

    @SchemaMapping(typeName = "PodcastEpisode", field = "downloaded")
    public boolean downloaded(PodcastEpisodeEntity episode) {
        return episode.getMediaFileEntities() != null && !episode.getMediaFileEntities().isEmpty();
    }

    @SchemaMapping(typeName = "PodcastEpisode", field = "durationInMilliseconds")
    public Long durationInMilliseconds(PodcastEpisodeEntity episode) {
        Long measured = episode.getMediaFileEntities() == null ? null
                : episode.getMediaFileEntities().stream()
                        .map(MediaFileEntity::getDurationInMilliseconds)
                        .filter(duration -> duration > 0)
                        .findFirst().orElse(null);
        if (measured != null) {
            return measured;
        }
        return episode.getDurationHintInMilliseconds() > 0 ? episode.getDurationHintInMilliseconds() : null;
    }

    @BatchMapping(typeName = "PodcastEpisode", field = "watchStatus")
    public Map<PodcastEpisodeEntity, List<WatchStatusEntity>> watchStatus(List<PodcastEpisodeEntity> episodes,
                                                                          Authentication authentication) {
        Map<UUID, List<WatchStatusEntity>> byEpisodeId = watchStatusRepository
                .findByUserEntityExternalIdAndPodcastEpisodeEntityIn(authentication.getName(), episodes,
                        Sort.by("dateUpdated").descending()).stream()
                .collect(Collectors.groupingBy(w -> w.getPodcastEpisodeEntity().getId()));
        return episodes.stream().collect(Collectors.toMap(e -> e, e -> byEpisodeId.getOrDefault(e.getId(), List.of())));
    }
}
