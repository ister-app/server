package app.ister.server.controller;

import app.ister.server.entitiy.PlayQueueEntity;
import app.ister.server.entitiy.PlayQueueItemEntity;
import app.ister.server.service.PlayQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
@Slf4j
public class PlayQueueController {
    @Autowired
    private PlayQueueService playQueueService;

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PlayQueueEntity createPlayQueue(@Argument UUID showId, @Argument UUID episodeId, Authentication authentication) {
        return playQueueService.createPlayQueue(showId, episodeId, authentication);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean updatePlayQueue(@Argument UUID id, @Argument long progressInMilliseconds, @Argument UUID playQueueItemId, Authentication authentication) {
        return playQueueService.updatePlayQueue(id, progressInMilliseconds, playQueueItemId, authentication);
    }

    @SchemaMapping(typeName = "PlayQueue", field = "playQueueItems")
    public List<PlayQueueItemEntity> playQueueItems(PlayQueueEntity playQueueEntity) {
        return playQueueEntity.getItems();
    }
}
