package app.ister.core.status;

import app.ister.core.eventdata.PlaybackCommandData;
import app.ister.core.eventdata.PlaybackStatusData;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;

/**
 * Bridges the status registries to GraphQL websocket subscribers.
 * <p>
 * Both sinks replay the latest value: a plain best-effort multicast sink silently
 * drops every emission that happens while it has zero subscribers — including the
 * window between a websocket subscribe message and the subscriber actually attaching.
 * Replay-latest never fails an emit — the RabbitMQ listener thread
 * that emits is never blocked — and a slow subscriber simply skips to the newest
 * value, which is exactly right for a status feed. It also gives every new subscriber
 * the current state immediately.
 */
@Component
public class ServerStatusBroadcaster {

    private final Sinks.Many<Object> activitySink = Sinks.many().replay().limit(1);
    private final Sinks.Many<List<PlaybackStatusData>> nowPlayingSink = Sinks.many().replay().limit(1);
    // Commands must NOT replay: a (re)subscriber would re-execute the last command.
    // Best-effort is correct here — with no live subscriber a command has no addressee.
    private final Sinks.Many<PlaybackCommandData> commandSink = Sinks.many().multicast().directBestEffort();

    public ServerStatusBroadcaster() {
        // Seed so a subscriber on a fresh node immediately receives the (empty) list
        // instead of waiting for the first playback heartbeat. Every registry change
        // emits the full new list, so the replayed value always equals current state.
        nowPlayingSink.tryEmitNext(List.of());
    }

    /** Emits a NodeActivityStatusData, QueueStatsStatusData or EventFailureStatusData. */
    public void emitActivity(Object statusData) {
        emitSerialized(activitySink, statusData);
    }

    public void emitNowPlaying(List<PlaybackStatusData> sessions) {
        emitSerialized(nowPlayingSink, sessions);
    }

    public void emitCommand(PlaybackCommandData command) {
        // Only emitted from the RabbitMQ listener thread, so no concurrent-emit spin
        // needed; a dropped command (no subscribers) is intended behaviour.
        commandSink.tryEmitNext(command);
    }

    /**
     * Sinks reject concurrent emission (FAIL_NON_SERIALIZED) and the callers race:
     * nowPlaying is emitted from both the RabbitMQ status listener and the session
     * sweeper's scheduler thread. Losing such an emit would leave the replay buffer —
     * a subscriber's only source of current state — stale, so briefly spin instead.
     */
    private static <T> void emitSerialized(Sinks.Many<T> sink, T value) {
        sink.emitNext(value, Sinks.EmitFailureHandler.busyLooping(java.time.Duration.ofMillis(500)));
    }

    public Flux<Object> activityFlux() {
        return activitySink.asFlux();
    }

    public Flux<List<PlaybackStatusData>> nowPlayingFlux() {
        return nowPlayingSink.asFlux();
    }

    public Flux<PlaybackCommandData> commandFlux() {
        return commandSink.asFlux();
    }
}
