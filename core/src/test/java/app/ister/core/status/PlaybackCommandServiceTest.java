package app.ister.core.status;

import app.ister.core.enums.PlaybackCommandType;
import app.ister.core.eventdata.PlaybackCommandData;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlaybackCommandServiceTest {

    @InjectMocks
    private PlaybackCommandService subject;

    @Mock
    private MessageSender messageSender;

    private PlaybackCommandData captureSentCommand() {
        ArgumentCaptor<PlaybackCommandData> captor = ArgumentCaptor.forClass(PlaybackCommandData.class);
        verify(messageSender).sendStatus(captor.capture());
        return captor.getValue();
    }

    @Test
    void publishSendsCommandWithAllFields() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        subject.publish(queueId, PlaybackCommandType.SEEK, 42000L, itemId);

        PlaybackCommandData data = captureSentCommand();
        assertEquals(queueId, data.getPlayQueueId());
        assertEquals(PlaybackCommandType.SEEK, data.getCommand());
        assertEquals(42000L, data.getPositionInMilliseconds());
        assertEquals(itemId, data.getPlayQueueItemId());
        assertNotNull(data.getTimestamp());
    }

    @Test
    void publishQueueChangedSendsQueueChangedWithoutPositionOrItem() {
        UUID queueId = UUID.randomUUID();

        subject.publishQueueChanged(queueId);

        PlaybackCommandData data = captureSentCommand();
        assertEquals(queueId, data.getPlayQueueId());
        assertEquals(PlaybackCommandType.QUEUE_CHANGED, data.getCommand());
        assertNull(data.getPositionInMilliseconds());
        assertNull(data.getPlayQueueItemId());
    }
}
