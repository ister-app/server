package app.ister.core.status;

import app.ister.core.enums.DevicePlatform;
import app.ister.core.eventdata.FollowerStatusData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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

    /** One following device, as the session owner sees it. Plain values only. */
    public record FollowerInfo(UUID userId, String userName, String deviceId, String deviceName,
                               DevicePlatform platform, Instant since) {
    }

    private record Entry(UUID userId, String userName, String deviceName, DevicePlatform platform,
                         Instant since, Instant receivedAt) {
    }

    private final Map<UUID, Map<String, Entry>> followers = new ConcurrentHashMap<>();
    /** Devices kicked by a session owner, per queue: barred from re-registering until they expire. */
    private final Map<UUID, Map<String, Instant>> kicked = new ConcurrentHashMap<>();
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
            register(data);
        } else {
            deregister(data);
        }
    }

    private void register(FollowerStatusData data) {
        Instant now = clock.instant();
        followers.computeIfAbsent(data.getPlayQueueId(), id -> new ConcurrentHashMap<>())
                .compute(data.getDeviceId(), (deviceId, existing) -> new Entry(
                        data.getUserId(), data.getUserName(), data.getDeviceName(), data.getPlatform(),
                        // A heartbeat keeps the moment this device started following.
                        existing == null ? now : existing.since(), now));
    }

    private void deregister(FollowerStatusData data) {
        // Deregistration by the follower only removes the caller's own entry: the user id must
        // match, so one user cannot deregister another user's device by reusing its device id.
        // A forced deregistration is the session owner kicking someone, and skips that check.
        followers.computeIfPresent(data.getPlayQueueId(), (queueId, devices) -> {
            devices.computeIfPresent(data.getDeviceId(), (deviceId, entry) ->
                    data.isForced() || entry.userId().equals(data.getUserId()) ? null : entry);
            return devices.isEmpty() ? null : devices;
        });
        if (data.isForced()) {
            // Without this the kicked client's next 20s heartbeat would simply re-register it.
            kicked.computeIfAbsent(data.getPlayQueueId(), id -> new ConcurrentHashMap<>())
                    .put(data.getDeviceId(), clock.instant());
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
        for (var iterator = kicked.entrySet().iterator(); iterator.hasNext(); ) {
            var queueEntry = iterator.next();
            queueEntry.getValue().values().removeIf(kickedAt -> kickedAt.isBefore(cutoff));
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

    /** Every following device of the queue, oldest registration first. */
    public List<FollowerInfo> followers(UUID playQueueId) {
        Map<String, Entry> devices = followers.get(playQueueId);
        if (devices == null) {
            return List.of();
        }
        return devices.entrySet().stream()
                .map(entry -> new FollowerInfo(entry.getValue().userId(), entry.getValue().userName(),
                        entry.getKey(), entry.getValue().deviceName(), entry.getValue().platform(),
                        entry.getValue().since()))
                .sorted(Comparator.comparing(FollowerInfo::since))
                .toList();
    }

    /** Whether the device was kicked off this queue recently and may not re-register yet. */
    public boolean isKicked(UUID playQueueId, String deviceId) {
        Map<String, Instant> devices = kicked.get(playQueueId);
        return devices != null && devices.containsKey(deviceId);
    }
}
