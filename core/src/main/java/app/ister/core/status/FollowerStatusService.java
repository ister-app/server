package app.ister.core.status;

import app.ister.core.enums.DevicePlatform;
import app.ister.core.eventdata.FollowerStatusData;
import app.ister.core.service.MessageSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes a follower (listen-along) registration on the status exchange. Called from the
 * followPlayQueue mutation on the API request thread; only plain values cross into the message.
 */
@Service
public class FollowerStatusService {

    private final MessageSender messageSender;

    public FollowerStatusService(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    public void publish(UUID playQueueId, String deviceId, UUID userId, String userName,
                        String deviceName, DevicePlatform platform, boolean active) {
        messageSender.sendStatus(FollowerStatusData.builder()
                .playQueueId(playQueueId)
                .deviceId(deviceId)
                .userId(userId)
                .userName(userName)
                .deviceName(deviceName)
                .platform(platform)
                .active(active)
                .timestamp(Instant.now())
                .build());
    }

    /**
     * The session owner kicking one following device: removes the entry whatever user it belongs
     * to, and bars that device from re-registering until the kick expires.
     */
    public void publishKick(UUID playQueueId, String deviceId, UUID userId) {
        messageSender.sendStatus(FollowerStatusData.builder()
                .playQueueId(playQueueId)
                .deviceId(deviceId)
                .userId(userId)
                .active(false)
                .forced(true)
                .timestamp(Instant.now())
                .build());
    }
}
