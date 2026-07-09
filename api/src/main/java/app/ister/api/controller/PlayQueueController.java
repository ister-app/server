package app.ister.api.controller;

import app.ister.api.dto.CreatePlayQueueInput;
import app.ister.api.dto.StreamSettingsInput;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.PlayQueuePrefetchService;
import app.ister.core.service.PlayQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
@Slf4j
public class PlayQueueController {
    private final PlayQueueService playQueueService;

    private final EpisodeRepository episodeRepository;

    private final MovieRepository movieRepository;

    private final TrackRepository trackRepository;

    private final PlayQueuePrefetchService playQueuePrefetchService;

    public PlayQueueController(PlayQueueService playQueueService, EpisodeRepository episodeRepository, MovieRepository movieRepository, TrackRepository trackRepository, PlayQueuePrefetchService playQueuePrefetchService) {
        this.playQueueService = playQueueService;
        this.episodeRepository = episodeRepository;
        this.movieRepository = movieRepository;
        this.trackRepository = trackRepository;
        this.playQueuePrefetchService = playQueuePrefetchService;
    }

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
                                                     @Argument StreamSettingsInput streamSettings, Authentication authentication) {
        PlayQueueService.StreamSettings settings = streamSettings == null ? null
                : new PlayQueueService.StreamSettings(streamSettings.direct(), streamSettings.transcode(), streamSettings.subtitleFormat());
        Optional<PlayQueueEntity> playQueue = playQueueService.updatePlayQueue(id, progressInMilliseconds, playQueueItemId, settings, authentication);
        playQueue.ifPresent(queue -> playQueuePrefetchService.maybePrefetchNext(queue, playQueueItemId, progressInMilliseconds));
        return playQueue;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity movePlayQueueItem(@Argument UUID playQueueId, @Argument UUID playQueueItemId, @Argument UUID afterPlayQueueItemId, Authentication authentication) {
        return playQueueService.movePlayQueueItem(playQueueId, playQueueItemId, afterPlayQueueItemId, authentication);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity removePlayQueueItem(@Argument UUID playQueueId, @Argument UUID playQueueItemId, Authentication authentication) {
        return playQueueService.removePlayQueueItem(playQueueId, playQueueItemId, authentication);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity addPlayQueueItem(@Argument UUID playQueueId, @Argument MediaType mediaType, @Argument UUID mediaId, @Argument UUID afterPlayQueueItemId, Authentication authentication) {
        return playQueueService.addPlayQueueItem(playQueueId, mediaType, mediaId, afterPlayQueueItemId, authentication);
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
}
