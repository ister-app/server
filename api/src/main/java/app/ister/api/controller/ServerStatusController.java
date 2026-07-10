package app.ister.api.controller;

import app.ister.api.dto.PlaybackSession;
import app.ister.api.dto.ServerActivityEvent;
import app.ister.api.dto.ServerActivitySnapshot;
import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.eventdata.QueueStatsStatusData;
import app.ister.core.status.NodeActivityRegistry;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.QueueStatsRegistry;
import app.ister.core.status.RecentFailuresBuffer;
import app.ister.core.status.ServerStatusBroadcaster;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

import java.util.List;

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

    public ServerStatusController(ServerStatusBroadcaster broadcaster, NodeActivityRegistry nodeActivityRegistry,
                                  QueueStatsRegistry queueStatsRegistry, RecentFailuresBuffer recentFailuresBuffer,
                                  PlaybackSessionRegistry playbackSessionRegistry) {
        this.broadcaster = broadcaster;
        this.nodeActivityRegistry = nodeActivityRegistry;
        this.queueStatsRegistry = queueStatsRegistry;
        this.recentFailuresBuffer = recentFailuresBuffer;
        this.playbackSessionRegistry = playbackSessionRegistry;
    }

    @PreAuthorize("hasRole('user')")
    @SubscriptionMapping
    public Flux<ServerActivityEvent> serverActivity() {
        return broadcaster.activityFlux().mapNotNull(ServerStatusController::toEvent);
    }

    @PreAuthorize("hasRole('user')")
    @SubscriptionMapping
    public Flux<List<PlaybackSession>> nowPlaying() {
        // The broadcaster replays the latest list, so the current state arrives
        // immediately on subscribe; every registry change re-emits the full list.
        return broadcaster.nowPlayingFlux()
                .map(sessions -> sessions.stream().map(PlaybackSession::from).toList());
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public ServerActivitySnapshot serverActivitySnapshot() {
        return new ServerActivitySnapshot(
                nodeActivityRegistry.nodesSnapshot().stream().map(ServerActivityEvent::from).toList(),
                queueStatsRegistry.snapshot().stream().map(ServerActivityEvent.QueueStat::from).toList(),
                recentFailuresBuffer.snapshot().stream().map(ServerActivityEvent.EventFailure::from).toList(),
                playbackSessionRegistry.snapshot().stream().map(PlaybackSession::from).toList());
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
