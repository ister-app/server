package app.ister.api.controller;

import app.ister.core.entitiy.EpisodeEntity;
import app.ister.core.entitiy.MovieEntity;
import app.ister.core.entitiy.PlayQueueEntity;
import app.ister.core.entitiy.PlayQueueItemEntity;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
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
import java.util.stream.Collectors;

@Controller
@Slf4j
public class PlayQueueController {
    private final PlayQueueService playQueueService;

    private final EpisodeRepository episodeRepository;

    private final MovieRepository movieRepository;

    public PlayQueueController(PlayQueueService playQueueService, EpisodeRepository episodeRepository, MovieRepository movieRepository) {
        this.playQueueService = playQueueService;
        this.episodeRepository = episodeRepository;
        this.movieRepository = movieRepository;
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<PlayQueueEntity> getPlayQueue(@Argument UUID id, Authentication authentication) {
        log.debug("Getting play queue for user: {}, play queue id: {}", authentication.getName(), id);
        return playQueueService.getPlayQueue(id);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity createPlayQueueForShow(@Argument UUID showId, @Argument UUID episodeId, Authentication authentication) {
        return playQueueService.createPlayQueueForShow(showId, episodeId, authentication);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity createPlayQueueForMovie(@Argument UUID movieId, Authentication authentication) {
        return playQueueService.createPlayQueueForMovie(movieId, authentication);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Optional<PlayQueueEntity> updatePlayQueue(@Argument UUID id, @Argument long progressInMilliseconds, @Argument UUID playQueueItemId, Authentication authentication) {
        return playQueueService.updatePlayQueue(id, progressInMilliseconds, playQueueItemId, authentication);
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
                    .collect(Collectors.toList());
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
                    .collect(Collectors.toList());
        }

        int from = Math.max(0, currentIdx - 10);
        int to   = Math.min(all.size(), currentIdx + 11); // exclusive upper bound

        return all.subList(from, to);
    }

    @SchemaMapping(typeName = "PlayQueue", field = "currentItemEpisode")
    public Optional<EpisodeEntity> currentItemEpisode(PlayQueueEntity playQueueEntity) {
        UUID currentItem = playQueueEntity.getCurrentItem();
        return currentItem != null ? episodeRepository.findById(currentItem) : Optional.empty();
    }

    @SchemaMapping(typeName = "PlayQueue", field = "currentItemMovie")
    public Optional<MovieEntity> currentItemMovie(PlayQueueEntity playQueueEntity) {
        UUID currentItem = playQueueEntity.getCurrentItem();
        return currentItem != null ? movieRepository.findById(currentItem) : Optional.empty();
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
}
