package app.ister.api.dto;

import app.ister.core.entity.DeviceEntity;
import app.ister.core.enums.DevicePlatform;

import java.util.UUID;

/** GraphQL view of one of the calling user's registered devices. */
public record Device(
        UUID deviceId,
        String name,
        DevicePlatform platform,
        boolean online,
        String lastSeenAt,
        String createdAt) {

    public static Device from(DeviceEntity entity, boolean online) {
        return new Device(entity.getDeviceId(), entity.getName(), entity.getPlatform(), online,
                String.valueOf(entity.getLastSeenAt()), String.valueOf(entity.getDateCreated()));
    }
}
