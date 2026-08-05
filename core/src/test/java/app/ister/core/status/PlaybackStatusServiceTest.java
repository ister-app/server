package app.ister.core.status;

import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayState;
import app.ister.core.enums.RemoteControlScope;
import app.ister.core.enums.RepeatMode;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlaybackStatusServiceTest {

    @Mock
    private MessageSender messageSender;

    private PlaybackStatusService subject;

    @BeforeEach
    void setUp() {
        subject = new PlaybackStatusService(messageSender, "node1");
    }

    private final UUID deviceId = UUID.randomUUID();

    private PlaybackStatusData publish(PlayState playState) {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID artworkId = UUID.randomUUID();

        subject.publishHeartbeat(queueId, itemId, userId, "external-id", "Gerben", MediaType.EPISODE,
                mediaId, "Title", 1000L, artworkId, 500L, playState,
                RemoteControlScope.ALLOWLIST, java.util.List.of(UUID.randomUUID()),
                deviceId, "Woonkamer",
                480L, 1_760_000_000_000L, RepeatMode.ALL);

        ArgumentCaptor<PlaybackStatusData> captor = ArgumentCaptor.forClass(PlaybackStatusData.class);
        verify(messageSender).sendStatus(captor.capture());
        return captor.getValue();
    }

    @Test
    void publishHeartbeatSendsAllFields() {
        PlaybackStatusData data = publish(PlayState.PAUSED);

        assertEquals("node1", data.getNodeName());
        assertEquals("external-id", data.getUserExternalId());
        assertEquals("Gerben", data.getUserName());
        assertEquals(RepeatMode.ALL, data.getRepeatMode());
        assertEquals(MediaType.EPISODE, data.getMediaType());
        assertEquals("Title", data.getTitle());
        assertEquals(1000L, data.getDurationInMilliseconds());
        assertEquals(500L, data.getProgressInMilliseconds());
        assertEquals(PlayState.PAUSED, data.getPlayState());
        assertEquals(RemoteControlScope.ALLOWLIST, data.getControlScopeOverride());
        assertEquals(1, data.getControlAllowedUserIds().size());
        assertEquals(deviceId, data.getDeviceId());
        assertEquals("Woonkamer", data.getDeviceName());
        assertEquals(480L, data.getAnchorPositionMs());
        assertEquals(1_760_000_000_000L, data.getAnchorServerTimeMs());
        assertNotNull(data.getTimestamp());
    }

    @Test
    void publishHeartbeatDefaultsToPlayingWhenPlayStateIsNull() {
        PlaybackStatusData data = publish(null);

        assertEquals(PlayState.PLAYING, data.getPlayState());
    }
}
