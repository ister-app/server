package app.ister.api.dto;

import app.ister.core.enums.DevicePlatform;
import app.ister.core.status.FollowerRegistry;

import java.util.UUID;

/** GraphQL view of one device listening along with a session (see FollowerRegistry in core). */
public record SessionFollower(
        UUID userId,
        String userName,
        String deviceId,
        String deviceName,
        DevicePlatform platform,
        String since) {

    public static SessionFollower from(FollowerRegistry.FollowerInfo info) {
        return new SessionFollower(info.userId(), info.userName(), info.deviceId(),
                info.deviceName(), info.platform(), String.valueOf(info.since()));
    }
}
