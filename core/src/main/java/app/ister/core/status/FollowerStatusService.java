package app.ister.core.status;

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

    public void publish(UUID playQueueId, String deviceId, UUID userId, boolean active) {
        messageSender.sendStatus(FollowerStatusData.builder()
                .playQueueId(playQueueId)
                .deviceId(deviceId)
                .userId(userId)
                .active(active)
                .timestamp(Instant.now())
                .build());
    }
}
