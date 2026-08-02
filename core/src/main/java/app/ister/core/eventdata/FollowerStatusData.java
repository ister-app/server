package app.ister.core.eventdata;

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
    /** False deregisters the device (user stopped following). */
    private boolean active;
    private Instant timestamp;
}
