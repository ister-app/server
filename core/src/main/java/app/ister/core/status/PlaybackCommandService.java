package app.ister.core.status;

import app.ister.core.enums.PlaybackCommandType;
import app.ister.core.eventdata.PlaybackCommandData;
import app.ister.core.service.MessageSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes a remote-control command on the status exchange. Called from the
 * sendPlaybackCommand mutation (and from the queue-edit mutations for QUEUE_CHANGED)
 * on the API request thread; only plain values cross into the message.
 */
@Service
public class PlaybackCommandService {

    private final MessageSender messageSender;

    public PlaybackCommandService(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    public void publish(UUID playQueueId, PlaybackCommandType command, Long positionInMilliseconds, UUID playQueueItemId) {
        messageSender.sendStatus(PlaybackCommandData.builder()
                .playQueueId(playQueueId)
                .command(command)
                .positionInMilliseconds(positionInMilliseconds)
                .playQueueItemId(playQueueItemId)
                .timestamp(Instant.now())
                .build());
    }

    public void publishQueueChanged(UUID playQueueId) {
        publish(playQueueId, PlaybackCommandType.QUEUE_CHANGED, null, null);
    }
}
