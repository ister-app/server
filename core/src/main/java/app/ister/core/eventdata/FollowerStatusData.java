package app.ister.core.eventdata;

import app.ister.core.enums.DevicePlatform;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Follower (listen-along) registration heartbeat, published on the status fan-out exchange from
 * the followPlayQueue mutation. Reaches every node, so the node handling the session owner's
 * updatePlayQueue knows which users are following and can write their watch status. A follower
 * that stops sending these is dropped after the session timeout. Carries only plain values.
 *
 * <p>The display names travel with the heartbeat rather than being looked up when the follower
 * list is read: reads happen on the now-playing sink's thread and in the sessionFollowers query,
 * and neither should touch the database per follower.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowerStatusData {
    private UUID playQueueId;
    /** Client-generated install id; distinguishes two devices of the same user. */
    private String deviceId;
    private UUID userId;
    /** Display name of the following user, resolved when the heartbeat was published. */
    private String userName;
    /** Registered device name; null when the follower never called registerDevice. */
    private String deviceName;
    /** Registered device platform; null without a device registration. */
    private DevicePlatform platform;
    /** False deregisters the device (user stopped following). */
    private boolean active;
    /**
     * A deregistration by the session owner (kick) instead of by the follower itself: it removes
     * the entry whatever user id it carries, and bars the device from re-registering for a while.
     */
    private boolean forced;
    private Instant timestamp;
}
