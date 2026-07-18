package app.ister.api.controller;

import app.ister.api.dto.PlaybackCommand;
import app.ister.core.enums.PlaybackCommandType;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.service.PlaybackSharingService;
import app.ister.core.service.UserService;
import app.ister.core.status.PlaybackCommandService;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.ServerStatusBroadcaster;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Remote control ("party mode"): the owner, and any user the owner (or the session's per-session
 * override) allows, may send transport commands to the client playing a queue. Commands travel over
 * the status fan-out exchange, so they reach the playing client regardless of which node holds its
 * websocket; a queue without a live subscriber simply drops them.
 */
@Controller
public class PlaybackCommandController {

    private final PlaybackCommandService playbackCommandService;
    private final PlaybackSessionRegistry playbackSessionRegistry;
    private final ServerStatusBroadcaster broadcaster;
    private final PlaybackSharingService playbackSharingService;
    private final UserService userService;

    public PlaybackCommandController(PlaybackCommandService playbackCommandService,
                                     PlaybackSessionRegistry playbackSessionRegistry,
                                     ServerStatusBroadcaster broadcaster,
                                     PlaybackSharingService playbackSharingService,
                                     UserService userService) {
        this.playbackCommandService = playbackCommandService;
        this.playbackSessionRegistry = playbackSessionRegistry;
        this.broadcaster = broadcaster;
        this.playbackSharingService = playbackSharingService;
        this.userService = userService;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean sendPlaybackCommand(@Argument UUID playQueueId, @Argument PlaybackCommandType command,
                                       @Argument Long positionInMilliseconds, @Argument UUID playQueueItemId,
                                       Authentication authentication) {
        // The registry is cluster-wide (status fan-out), so no live session anywhere means the
        // command has nowhere to go. Both "no session" and "not allowed to control" return false,
        // so a denied caller cannot tell an unshared session from a stopped one (deny = not-found).
        Optional<PlaybackStatusData> session = playbackSessionRegistry.find(playQueueId);
        if (session.isEmpty()) {
            return false;
        }
        PlaybackStatusData data = session.get();
        UUID viewerId = userService.getOrCreateUser(authentication).getId();
        Set<UUID> sessionAllowed = data.getControlAllowedUserIds() == null
                ? Set.of() : new HashSet<>(data.getControlAllowedUserIds());
        if (!playbackSharingService.canControl(viewerId, data.getUserId(), data.getControlScopeOverride(), sessionAllowed)) {
            return false;
        }
        playbackCommandService.publish(playQueueId, command, positionInMilliseconds, playQueueItemId);
        return true;
    }

    @PreAuthorize("hasRole('user')")
    @SubscriptionMapping
    public Flux<PlaybackCommand> playbackCommands(@Argument UUID playQueueId) {
        return broadcaster.commandFlux()
                .filter(data -> playQueueId.equals(data.getPlayQueueId()))
                .map(PlaybackCommand::from);
    }
}
