package app.ister.api.controller;

import app.ister.api.dto.CreatePlayQueueInput;
import app.ister.api.dto.StreamSettingsInput;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.PlayQueuePrefetchService;
import app.ister.core.service.PlayQueueService;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.PlaybackStatusService;
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
public class PlayQueueController {
    private final PlayQueueService playQueueService;

    private final EpisodeRepository episodeRepository;

    private final MovieRepository movieRepository;

    private final TrackRepository trackRepository;

    private final PlayQueuePrefetchService playQueuePrefetchService;

    private final PlaybackStatusService playbackStatusService;

    private final PlaybackSessionRegistry playbackSessionRegistry;

    public PlayQueueController(PlayQueueService playQueueService, EpisodeRepository episodeRepository, MovieRepository movieRepository, TrackRepository trackRepository, PlayQueuePrefetchService playQueuePrefetchService, PlaybackStatusService playbackStatusService, PlaybackSessionRegistry playbackSessionRegistry) {
        this.playQueueService = playQueueService;
        this.episodeRepository = episodeRepository;
        this.movieRepository = movieRepository;
        this.trackRepository = trackRepository;
        this.playQueuePrefetchService = playQueuePrefetchService;
        this.playbackStatusService = playbackStatusService;
        this.playbackSessionRegistry = playbackSessionRegistry;
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
                progressInMilliseconds,
                playState);
    }

    private static UUID mediaIdOf(PlayQueueItemEntity item) {
        return switch (item.getType()) {
            case MOVIE -> item.getMovieEntityId();
            case EPISODE -> item.getEpisodeEntityId();
            case TRACK -> item.getTrackEntityId();
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
        };
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
