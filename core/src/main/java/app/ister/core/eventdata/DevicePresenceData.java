package app.ister.core.eventdata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Device presence heartbeat, published on the status fan-out exchange from the pingDevice
 * (and registerDevice) mutations every ~20s per online device. Feeds the in-memory
 * {@link app.ister.core.status.DevicePresenceRegistry} on every node; a device that stops
 * pinging expires after the session timeout. Carries only plain values.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevicePresenceData {
    private UUID ownerUserId;
    private UUID deviceId;
    private Instant timestamp;
}
