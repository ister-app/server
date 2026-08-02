package app.ister.core.status;

import app.ister.core.eventdata.FollowerStatusData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Active listen-along followers across the cluster, keyed by play queue, then by the follower's
 * device id — so two devices of the same user are distinct entries but count as one user for
 * watch status. Expiry mirrors {@link PlaybackSessionRegistry}: based on the local receive time
 * of the last follow heartbeat, swept by {@link PlaybackSessionSweeper}.
 */
@Component
public class FollowerRegistry {

    private record Entry(UUID userId, Instant receivedAt) {
    }

    private final Map<UUID, Map<String, Entry>> followers = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public FollowerRegistry() {
        this(Clock.systemUTC());
    }

    FollowerRegistry(Clock clock) {
        this.clock = clock;
    }

    public void update(FollowerStatusData data) {
        if (data.getPlayQueueId() == null || data.getDeviceId() == null || data.getUserId() == null) {
            return;
        }
        if (data.isActive()) {
            followers.computeIfAbsent(data.getPlayQueueId(), id -> new ConcurrentHashMap<>())
                    .put(data.getDeviceId(), new Entry(data.getUserId(), clock.instant()));
        } else {
            // Deregistration only removes the caller's own entry: the user id must match, so
            // one user cannot deregister another user's device by reusing its device id.
            followers.computeIfPresent(data.getPlayQueueId(), (queueId, devices) -> {
                devices.computeIfPresent(data.getDeviceId(),
                        (deviceId, entry) -> entry.userId().equals(data.getUserId()) ? null : entry);
                return devices.isEmpty() ? null : devices;
            });
        }
    }

    /** Drops followers whose client stopped sending follow heartbeats; returns true when anything expired. */
    public boolean removeExpired(Duration timeout) {
        Instant cutoff = clock.instant().minus(timeout);
        boolean removed = false;
        for (var iterator = followers.entrySet().iterator(); iterator.hasNext(); ) {
            var queueEntry = iterator.next();
            removed |= queueEntry.getValue().values().removeIf(entry -> entry.receivedAt().isBefore(cutoff));
            if (queueEntry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
        return removed;
    }

    /** Distinct user ids currently following the queue; a user on two devices appears once. */
    public Set<UUID> activeUserIds(UUID playQueueId) {
        Map<String, Entry> devices = followers.get(playQueueId);
        if (devices == null) {
            return Set.of();
        }
        return devices.values().stream().map(Entry::userId).collect(Collectors.toUnmodifiableSet());
    }

    /** Number of following devices (not users) of the queue, for the now-playing card. */
    public int deviceCount(UUID playQueueId) {
        Map<String, Entry> devices = followers.get(playQueueId);
        return devices == null ? 0 : devices.size();
    }
}
