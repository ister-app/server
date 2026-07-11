package app.ister.api.controller;

import app.ister.api.dto.PlaybackCommand;
import app.ister.core.enums.PlaybackCommandType;
import app.ister.core.status.PlaybackCommandService;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.ServerStatusBroadcaster;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Remote control ("party mode"): any authenticated user may send transport commands to
 * the client playing a queue. Commands travel over the status fan-out exchange, so they
 * reach the playing client regardless of which node holds its websocket; a queue without
 * a live subscriber simply drops them.
 */
@Controller
public class PlaybackCommandController {

    private final PlaybackCommandService playbackCommandService;
    private final PlaybackSessionRegistry playbackSessionRegistry;
    private final ServerStatusBroadcaster broadcaster;

    public PlaybackCommandController(PlaybackCommandService playbackCommandService,
                                     PlaybackSessionRegistry playbackSessionRegistry,
                                     ServerStatusBroadcaster broadcaster) {
        this.playbackCommandService = playbackCommandService;
        this.playbackSessionRegistry = playbackSessionRegistry;
        this.broadcaster = broadcaster;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean sendPlaybackCommand(@Argument UUID playQueueId, @Argument PlaybackCommandType command,
                                       @Argument Long positionInMilliseconds, @Argument UUID playQueueItemId) {
        playbackCommandService.publish(playQueueId, command, positionInMilliseconds, playQueueItemId);
        // Informational: whether an active session for this queue was known when sent.
        return playbackSessionRegistry.find(playQueueId).isPresent();
    }

    @PreAuthorize("hasRole('user')")
    @SubscriptionMapping
    public Flux<PlaybackCommand> playbackCommands(@Argument UUID playQueueId) {
        return broadcaster.commandFlux()
                .filter(data -> playQueueId.equals(data.getPlayQueueId()))
                .map(PlaybackCommand::from);
    }
}
