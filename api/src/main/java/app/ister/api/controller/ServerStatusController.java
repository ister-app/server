package app.ister.api.controller;

import app.ister.api.dto.PlaybackSession;
import app.ister.api.dto.ServerActivityEvent;
import app.ister.api.dto.ServerActivitySnapshot;
import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.eventdata.QueueStatsStatusData;
import app.ister.core.service.PlaybackSharingService;
import app.ister.core.service.UserService;
import app.ister.core.status.NodeActivityRegistry;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.QueueStatsRegistry;
import app.ister.core.status.RecentFailuresBuffer;
import app.ister.core.status.ServerStatusBroadcaster;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Live cluster status for clients: what every node is working on, queue depths,
 * recent failures and who is playing what. Subscriptions arrive over the GraphQL
 * websocket (see GraphQlWebSocketAuthConfig); the snapshot query provides the
 * initial state to render before the first update.
 */
@Controller
public class ServerStatusController {

    private final ServerStatusBroadcaster broadcaster;
    private final NodeActivityRegistry nodeActivityRegistry;
    private final QueueStatsRegistry queueStatsRegistry;
    private final RecentFailuresBuffer recentFailuresBuffer;
    private final PlaybackSessionRegistry playbackSessionRegistry;
    private final PlaybackSharingService playbackSharingService;
    private final UserService userService;

    public ServerStatusController(ServerStatusBroadcaster broadcaster, NodeActivityRegistry nodeActivityRegistry,
                                  QueueStatsRegistry queueStatsRegistry, RecentFailuresBuffer recentFailuresBuffer,
                                  PlaybackSessionRegistry playbackSessionRegistry,
                                  PlaybackSharingService playbackSharingService, UserService userService) {
        this.broadcaster = broadcaster;
        this.nodeActivityRegistry = nodeActivityRegistry;
        this.queueStatsRegistry = queueStatsRegistry;
        this.recentFailuresBuffer = recentFailuresBuffer;
        this.playbackSessionRegistry = playbackSessionRegistry;
        this.playbackSharingService = playbackSharingService;
        this.userService = userService;
    }

    @PreAuthorize("hasRole('user')")
    @SubscriptionMapping
    public Flux<ServerActivityEvent> serverActivity() {
        return broadcaster.activityFlux().mapNotNull(ServerStatusController::toEvent);
    }

    @PreAuthorize("hasRole('user')")
    @SubscriptionMapping
    public Flux<List<PlaybackSession>> nowPlaying(Authentication authentication) {
        // Each viewer sees only the sessions the owners shared with them (their own always). The
        // now-playing sink emits synchronously on the RabbitMQ listener thread, which must stay
        // DB-free — so resolve the viewer id and run the (cached) sharing lookups on boundedElastic.
        // The sink replays the latest list, so the current state still arrives immediately.
        return Mono.fromCallable(() -> userService.getOrCreateUser(authentication).getId())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(viewerId -> broadcaster.nowPlayingFlux()
                        .publishOn(Schedulers.boundedElastic())
                        .map(sessions -> visibleSessions(sessions, viewerId)));
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public ServerActivitySnapshot serverActivitySnapshot(Authentication authentication) {
        UUID viewerId = userService.getOrCreateUser(authentication).getId();
        return new ServerActivitySnapshot(
                nodeActivityRegistry.nodesSnapshot().stream().map(ServerActivityEvent::from).toList(),
                queueStatsRegistry.snapshot().stream().map(ServerActivityEvent.QueueStat::from).toList(),
                recentFailuresBuffer.snapshot().stream().map(ServerActivityEvent.EventFailure::from).toList(),
                visibleSessions(playbackSessionRegistry.snapshot(), viewerId));
    }

    /** Filters the session list to what {@code viewerId} may see and stamps each with a
     *  per-viewer {@code controllable} flag. Uses only in-memory session data plus the sharing
     *  service's per-owner cache, so it is safe to run off the request thread. */
    private List<PlaybackSession> visibleSessions(List<PlaybackStatusData> sessions, UUID viewerId) {
        return sessions.stream()
                .filter(session -> playbackSharingService.canView(viewerId, session.getUserId()))
                .map(session -> PlaybackSession.from(session, controllable(viewerId, session)))
                .toList();
    }

    private boolean controllable(UUID viewerId, PlaybackStatusData session) {
        Set<UUID> sessionAllowed = session.getControlAllowedUserIds() == null
                ? Set.of() : new HashSet<>(session.getControlAllowedUserIds());
        return playbackSharingService.canControl(viewerId, session.getUserId(),
                session.getControlScopeOverride(), sessionAllowed);
    }

    private static ServerActivityEvent toEvent(Object statusData) {
        return switch (statusData) {
            case NodeActivityStatusData data -> ServerActivityEvent.from(data);
            case QueueStatsStatusData data -> ServerActivityEvent.from(data);
            case EventFailureStatusData data -> ServerActivityEvent.from(data);
            case PlaybackStatusData _ -> null; // playback flows through nowPlaying
            default -> null;
        };
    }
}
