package app.ister.core.status;

import app.ister.core.eventdata.PlaybackStatusData;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Active playback sessions across the cluster, keyed by play queue. Expiry is based on
 * the local receive time of the last heartbeat (not the producer timestamp), so clock
 * skew between nodes cannot expire live sessions; the producer timestamp is only used
 * to drop out-of-order fan-out deliveries.
 */
@Component
public class PlaybackSessionRegistry {

    private record Entry(PlaybackStatusData data, Instant receivedAt) {
    }

    private final Map<java.util.UUID, Entry> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public PlaybackSessionRegistry() {
        this(Clock.systemUTC());
    }

    PlaybackSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    public void update(PlaybackStatusData heartbeat) {
        sessions.merge(heartbeat.getPlayQueueId(), new Entry(heartbeat, clock.instant()),
                (existing, incoming) -> isStale(existing.data(), incoming.data()) ? existing : incoming);
    }

    private static boolean isStale(PlaybackStatusData existing, PlaybackStatusData incoming) {
        return existing.getTimestamp() != null && incoming.getTimestamp() != null
                && incoming.getTimestamp().isBefore(existing.getTimestamp());
    }

    /**
     * Drops sessions whose client stopped sending heartbeats; returns true when
     * anything expired, so the caller knows to broadcast the new now-playing list.
     */
    public boolean removeExpired(Duration timeout) {
        Instant cutoff = clock.instant().minus(timeout);
        return sessions.values().removeIf(entry -> entry.receivedAt().isBefore(cutoff));
    }

    public List<PlaybackStatusData> snapshot() {
        return sessions.values().stream()
                .map(Entry::data)
                .sorted(Comparator.comparing(PlaybackStatusData::getUserName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
