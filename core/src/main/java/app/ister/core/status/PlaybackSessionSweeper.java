package app.ister.core.status;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Considers a playback session stopped when no heartbeat arrived for 60s (clients
 * update every ~5-15s; 60s tolerates jitter and a missed update). Runs on every node
 * independently — all nodes see the same heartbeats, so their views converge without
 * any cross-node stop coordination.
 */
@Component
public class PlaybackSessionSweeper {

    static final Duration SESSION_TIMEOUT = Duration.ofSeconds(60);

    private final PlaybackSessionRegistry registry;
    private final ServerStatusBroadcaster broadcaster;

    public PlaybackSessionSweeper(PlaybackSessionRegistry registry, ServerStatusBroadcaster broadcaster) {
        this.registry = registry;
        this.broadcaster = broadcaster;
    }

    @Scheduled(fixedDelay = 15000)
    public void sweep() {
        if (registry.removeExpired(SESSION_TIMEOUT)) {
            broadcaster.emitNowPlaying(registry.snapshot());
        }
    }
}
