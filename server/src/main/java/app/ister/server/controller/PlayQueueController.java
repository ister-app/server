package app.ister.server.controller;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.MovieEntity;
import app.ister.server.entitiy.PlayQueueEntity;
import app.ister.server.entitiy.PlayQueueItemEntity;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.MovieRepository;
import app.ister.server.service.PlayQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired
    private PlayQueueService playQueueService;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private MovieRepository movieRepository;

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

    @SchemaMapping(typeName = "PlayQueue", field = "playQueueItems")
    public List<PlayQueueItemEntity> playQueueItems(PlayQueueEntity playQueueEntity) {
        return playQueueEntity.getItems();
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
}
