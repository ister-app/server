package app.ister.api.controller;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.PodcastEpisodeDownloadRequestedData;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.MessageSender;
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
    private final WatchStatusRepository watchStatusRepository;
    private final MessageSender messageSender;

    @Value("${app.ister.server.name}")
    private String nodeName;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<PodcastEpisodeEntity> podcastEpisodeById(@Argument UUID id) {
        return podcastEpisodeRepository.findById(id);
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<PodcastEpisodeEntity> podcastEpisodes(
            @Argument UUID podcastId,
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size) {
        int pageSize = Math.clamp(size.orElse(25), 1, Paging.MAX_PAGE_SIZE);
        return podcastEpisodeRepository.findByPodcastEntityIdOrderByPublishedAtDesc(podcastId,
                PageRequest.of(Math.max(page.orElse(0), 0), pageSize));
    }

    /** Sends the download to THIS node's cache directory; the client polls `downloaded`. */
    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean downloadPodcastEpisode(@Argument UUID episodeId) {
        if (!podcastEpisodeRepository.existsById(episodeId)) {
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
