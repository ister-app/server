package app.ister.api.controller;

import app.ister.api.dto.PlaybackCommand;
import app.ister.core.enums.PlayState;
import app.ister.core.enums.PlaybackCommandType;
import app.ister.core.eventdata.PlaybackCommandData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.status.PlaybackCommandService;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.ServerStatusBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlaybackCommandControllerTest {

    private ServerStatusBroadcaster broadcaster;
    private PlaybackSessionRegistry playbackSessionRegistry;
    private PlaybackCommandController controller;

    @Mock
    private PlaybackCommandService playbackCommandService;

    @BeforeEach
    void setUp() {
        broadcaster = new ServerStatusBroadcaster();
        playbackSessionRegistry = new PlaybackSessionRegistry();
        controller = new PlaybackCommandController(playbackCommandService, playbackSessionRegistry, broadcaster);
    }

    private static PlaybackCommandData command(UUID playQueueId, PlaybackCommandType type) {
        return PlaybackCommandData.builder()
                .playQueueId(playQueueId)
                .command(type)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void playbackCommandsOnlyDeliversCommandsForTheSubscribedQueue() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<PlaybackCommand> received = new CopyOnWriteArrayList<>();
        controller.playbackCommands(mine).subscribe(received::add);

        broadcaster.emitCommand(command(other, PlaybackCommandType.PAUSE));
        broadcaster.emitCommand(command(mine, PlaybackCommandType.NEXT));

        assertEquals(1, received.size());
        assertEquals(PlaybackCommandType.NEXT, received.getFirst().command());
        assertEquals(mine, received.getFirst().playQueueId());
    }

    @Test
    void playbackCommandsDoesNotReplayToLateSubscriber() {
        // A replayed command would be re-executed by a (re)connecting client.
        UUID playQueueId = UUID.randomUUID();
        broadcaster.emitCommand(command(playQueueId, PlaybackCommandType.NEXT));

        List<PlaybackCommand> received = new CopyOnWriteArrayList<>();
        controller.playbackCommands(playQueueId).subscribe(received::add);

        assertTrue(received.isEmpty());
    }

    @Test
    void sendPlaybackCommandPublishesAndReportsKnownSession() {
        UUID playQueueId = UUID.randomUUID();
        playbackSessionRegistry.update(PlaybackStatusData.builder()
                .playQueueId(playQueueId)
                .userId(UUID.randomUUID())
                .playState(PlayState.PLAYING)
                .nodeName("node-a")
                .timestamp(Instant.now())
                .build());

        boolean known = controller.sendPlaybackCommand(playQueueId, PlaybackCommandType.SEEK, 12345L, null);

        assertTrue(known);
        verify(playbackCommandService).publish(playQueueId, PlaybackCommandType.SEEK, 12345L, null);
    }

    @Test
    void sendPlaybackCommandReportsUnknownSession() {
        UUID playQueueId = UUID.randomUUID();

        boolean known = controller.sendPlaybackCommand(playQueueId, PlaybackCommandType.PAUSE, null, null);

        assertFalse(known);
        verify(playbackCommandService).publish(playQueueId, PlaybackCommandType.PAUSE, null, null);
    }

    @Test
    void commandRoundTripThroughServiceStubDeliversAllFields() {
        // Simulate the fan-out loop: publish → (rabbit) → broadcaster.emitCommand.
        doAnswer(invocation -> {
            broadcaster.emitCommand(PlaybackCommandData.builder()
                    .playQueueId(invocation.getArgument(0))
                    .command(invocation.getArgument(1))
                    .positionInMilliseconds(invocation.getArgument(2))
                    .playQueueItemId(invocation.getArgument(3))
                    .timestamp(Instant.now())
                    .build());
            return null;
        }).when(playbackCommandService).publish(any(), any(), any(), any());

        UUID playQueueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        List<PlaybackCommand> received = new CopyOnWriteArrayList<>();
        controller.playbackCommands(playQueueId).subscribe(received::add);

        controller.sendPlaybackCommand(playQueueId, PlaybackCommandType.SKIP_TO_ITEM, null, itemId);

        assertEquals(1, received.size());
        assertEquals(PlaybackCommandType.SKIP_TO_ITEM, received.getFirst().command());
        assertEquals(itemId, received.getFirst().playQueueItemId());
        assertNotNull(received.getFirst().timestamp());
    }
}
