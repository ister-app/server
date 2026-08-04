package app.ister.core.status;

import app.ister.core.enums.DeviceCommandType;
import app.ister.core.enums.MediaType;
import app.ister.core.eventdata.DeviceCommandData;
import app.ister.core.eventdata.DevicePresenceData;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeviceCommandServiceTest {

    @InjectMocks
    private DeviceCommandService subject;

    @Mock
    private MessageSender messageSender;

    @Test
    void publishStampsTimestampAndSendsCommand() {
        UUID owner = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        subject.publish(DeviceCommandData.builder()
                .ownerUserId(owner)
                .deviceId(device)
                .command(DeviceCommandType.PLAY_MEDIA)
                .mediaType(MediaType.MOVIE)
                .mediaId(mediaId)
                .build());

        ArgumentCaptor<DeviceCommandData> captor = ArgumentCaptor.forClass(DeviceCommandData.class);
        verify(messageSender).sendStatus(captor.capture());
        DeviceCommandData data = captor.getValue();
        assertEquals(owner, data.getOwnerUserId());
        assertEquals(device, data.getDeviceId());
        assertEquals(DeviceCommandType.PLAY_MEDIA, data.getCommand());
        assertEquals(MediaType.MOVIE, data.getMediaType());
        assertEquals(mediaId, data.getMediaId());
        assertNotNull(data.getTimestamp());
    }

    @Test
    void publishPresenceSendsOwnerScopedPing() {
        UUID owner = UUID.randomUUID();
        UUID device = UUID.randomUUID();

        subject.publishPresence(owner, device);

        ArgumentCaptor<DevicePresenceData> captor = ArgumentCaptor.forClass(DevicePresenceData.class);
        verify(messageSender).sendStatus(captor.capture());
        assertEquals(owner, captor.getValue().getOwnerUserId());
        assertEquals(device, captor.getValue().getDeviceId());
        assertNotNull(captor.getValue().getTimestamp());
    }
}
