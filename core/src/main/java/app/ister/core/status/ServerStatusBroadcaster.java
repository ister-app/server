package app.ister.core.status;

import app.ister.core.eventdata.PlaybackStatusData;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;

/**
 * Bridges the status registries to GraphQL websocket subscribers. Uses best-effort
 * multicast sinks: a slow websocket client drops updates instead of ever blocking the
 * RabbitMQ listener thread that emits, and emitting without subscribers is a no-op.
 */
@Component
public class ServerStatusBroadcaster {

    private final Sinks.Many<Object> activitySink = Sinks.many().multicast().directBestEffort();
    private final Sinks.Many<List<PlaybackStatusData>> nowPlayingSink = Sinks.many().multicast().directBestEffort();

    /** Emits a NodeActivityStatusData, QueueStatsStatusData or EventFailureStatusData. */
    public void emitActivity(Object statusData) {
        activitySink.tryEmitNext(statusData);
    }

    public void emitNowPlaying(List<PlaybackStatusData> sessions) {
        nowPlayingSink.tryEmitNext(sessions);
    }

    public Flux<Object> activityFlux() {
        return activitySink.asFlux();
    }

    public Flux<List<PlaybackStatusData>> nowPlayingFlux() {
        return nowPlayingSink.asFlux();
    }
}
