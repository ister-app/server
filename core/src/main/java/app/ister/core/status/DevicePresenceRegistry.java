package app.ister.core.status;

import app.ister.core.eventdata.DevicePresenceData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which registered devices are online right now, cluster-wide, keyed by owner user and then by
 * device id (device ids are only unique per user). Fed by the presence heartbeats on the status
 * fan-out; expiry mirrors {@link FollowerRegistry}: based on the local receive time of the last
 * ping, swept by {@link PlaybackSessionSweeper}. Only online devices are valid targets for
 * device commands.
 */
@Component
public class DevicePresenceRegistry {

    private final Map<UUID, Map<UUID, Instant>> online = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public DevicePresenceRegistry() {
        this(Clock.systemUTC());
    }

    DevicePresenceRegistry(Clock clock) {
        this.clock = clock;
    }

    public void update(DevicePresenceData data) {
        if (data.getOwnerUserId() == null || data.getDeviceId() == null) {
            return;
        }
        online.computeIfAbsent(data.getOwnerUserId(), id -> new ConcurrentHashMap<>())
                .put(data.getDeviceId(), clock.instant());
    }

    /** Drops devices whose client stopped pinging; returns true when anything expired. */
    public boolean removeExpired(Duration timeout) {
        Instant cutoff = clock.instant().minus(timeout);
        boolean removed = false;
        for (var iterator = online.entrySet().iterator(); iterator.hasNext(); ) {
            var userEntry = iterator.next();
            removed |= userEntry.getValue().values().removeIf(receivedAt -> receivedAt.isBefore(cutoff));
            if (userEntry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
        return removed;
    }

    public boolean isOnline(UUID ownerUserId, UUID deviceId) {
        Map<UUID, Instant> devices = online.get(ownerUserId);
        return devices != null && devices.containsKey(deviceId);
    }

    /** The user's currently online device ids, for the myDevices online flag. */
    public Set<UUID> onlineDeviceIds(UUID ownerUserId) {
        Map<UUID, Instant> devices = online.get(ownerUserId);
        return devices == null ? Set.of() : Set.copyOf(devices.keySet());
    }
}
