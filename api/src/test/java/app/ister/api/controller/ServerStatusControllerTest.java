package app.ister.api.controller;

import app.ister.api.dto.PlaybackSession;
import app.ister.api.dto.ServerActivityEvent;
import app.ister.api.dto.ServerActivitySnapshot;
import app.ister.core.enums.PlayState;
import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.eventdata.QueueStatsStatusData;
import app.ister.core.entity.UserEntity;
import app.ister.core.service.PlaybackSharingService;
import app.ister.core.service.UserService;
import app.ister.core.status.NodeActivityRegistry;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.QueueStatsRegistry;
import app.ister.core.status.RecentFailuresBuffer;
import app.ister.core.status.ServerStatusBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerStatusControllerTest {

    private ServerStatusBroadcaster broadcaster;
    private NodeActivityRegistry nodeActivityRegistry;
    private QueueStatsRegistry queueStatsRegistry;
    private RecentFailuresBuffer recentFailuresBuffer;
    private PlaybackSessionRegistry playbackSessionRegistry;
    private PlaybackSharingService playbackSharingService;
    private UserService userService;
    private ServerStatusController controller;
    private final Authentication authentication = mock(Authentication.class);
    private final UUID viewerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        broadcaster = new ServerStatusBroadcaster();
        nodeActivityRegistry = new NodeActivityRegistry();
        queueStatsRegistry = new QueueStatsRegistry();
        recentFailuresBuffer = new RecentFailuresBuffer();
        playbackSessionRegistry = new PlaybackSessionRegistry();
        playbackSharingService = mock(PlaybackSharingService.class);
        userService = mock(UserService.class);
        when(userService.getOrCreateUser(authentication)).thenReturn(UserEntity.builder().id(viewerId).build());
        // Default the visibility gate open; the filtering test overrides it per owner.
        lenient().when(playbackSharingService.canView(any(), any())).thenReturn(true);
        lenient().when(playbackSharingService.canControl(any(), any(), any(), any())).thenReturn(true);
        controller = new ServerStatusController(broadcaster, nodeActivityRegistry, queueStatsRegistry,
                recentFailuresBuffer, playbackSessionRegistry, playbackSharingService, userService);
    }

    private static PlaybackStatusData playback(UUID playQueueId) {
        return playback(playQueueId, UUID.randomUUID());
    }

    private static PlaybackStatusData playback(UUID playQueueId, UUID userId) {
        return PlaybackStatusData.builder()
                .playQueueId(playQueueId)
                .userId(userId)
                .userName("user")
                .playState(PlayState.PLAYING)
                .nodeName("node-a")
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void serverActivityMapsStatusMessagesToEvents() {
        List<ServerActivityEvent> received = new CopyOnWriteArrayList<>();
        controller.serverActivity().subscribe(received::add);

        broadcaster.emitActivity(new NodeActivityStatusData("node-a", Instant.now(), List.of(), 3, 1));
        broadcaster.emitActivity(new QueueStatsStatusData("node-a", Instant.now(),
                List.of(new QueueStatsStatusData.QueueStat("app.ister.server.MovieFound", 5, 3))));
        broadcaster.emitActivity(EventFailureStatusData.builder().nodeName("node-a").timestamp(Instant.now())
                .queue("app.ister.server.MovieFound").errorMessage("boom").build());

        assertEquals(3, received.size());
        assertEquals(ServerActivityEvent.ServerActivityEventType.NODE_ACTIVITY, received.get(0).type());
        assertEquals(3L, received.get(0).processedCount());
        assertEquals(ServerActivityEvent.ServerActivityEventType.QUEUE_STATS, received.get(1).type());
        assertEquals(5, received.get(1).queueStats().getFirst().depth());
        assertEquals(ServerActivityEvent.ServerActivityEventType.FAILURE, received.get(2).type());
        assertEquals("boom", received.get(2).failure().errorMessage());
    }

    @Test
    void nowPlayingStartsWithLatestStateThenUpdates() {
        // In production every registry update is followed by an emit (StatusEventListener),
        // so the replayed latest value always equals the current registry state.
        UUID first = UUID.randomUUID();
        playbackSessionRegistry.update(playback(first));
        broadcaster.emitNowPlaying(playbackSessionRegistry.snapshot());

        StepVerifier.create(controller.nowPlaying(authentication))
                .assertNext(sessions -> {
                    assertEquals(1, sessions.size());
                    assertEquals(first, sessions.getFirst().playQueueId());
                })
                .then(() -> {
                    playbackSessionRegistry.update(playback(UUID.randomUUID()));
                    broadcaster.emitNowPlaying(playbackSessionRegistry.snapshot());
                })
                .assertNext(sessions -> assertEquals(2, sessions.size()))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void nowPlayingEmitsEmptyListToSubscriberOnFreshNode() {
        StepVerifier.create(controller.nowPlaying(authentication))
                .assertNext(sessions -> assertTrue(sessions.isEmpty()))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void nowPlayingHidesSessionsTheViewerMayNotSeeAndStampsControllable() {
        UUID visibleOwner = UUID.randomUUID();
        UUID hiddenOwner = UUID.randomUUID();
        when(playbackSharingService.canView(viewerId, visibleOwner)).thenReturn(true);
        when(playbackSharingService.canView(viewerId, hiddenOwner)).thenReturn(false);
        when(playbackSharingService.canControl(eq(viewerId), eq(visibleOwner), any(), any())).thenReturn(false);

        UUID visibleQueue = UUID.randomUUID();
        playbackSessionRegistry.update(playback(visibleQueue, visibleOwner));
        playbackSessionRegistry.update(playback(UUID.randomUUID(), hiddenOwner));
        broadcaster.emitNowPlaying(playbackSessionRegistry.snapshot());

        StepVerifier.create(controller.nowPlaying(authentication))
                .assertNext(sessions -> {
                    assertEquals(1, sessions.size());
                    assertEquals(visibleQueue, sessions.getFirst().playQueueId());
                    assertFalse(sessions.getFirst().controllable());
                })
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void serverActivityReplaysLatestEventToLateSubscriber() {
        // The websocket transport may still be finishing the subscribe when an event is
        // emitted; replay-latest guarantees the newest event is not lost.
        broadcaster.emitActivity(new NodeActivityStatusData("node-a", Instant.now(), List.of(), 1, 0));

        List<ServerActivityEvent> received = new CopyOnWriteArrayList<>();
        controller.serverActivity().subscribe(received::add);

        assertEquals(1, received.size());
        assertEquals(ServerActivityEvent.ServerActivityEventType.NODE_ACTIVITY, received.getFirst().type());
    }

    @Test
    void snapshotAggregatesAllRegistries() {
        nodeActivityRegistry.updateNode(new NodeActivityStatusData("node-a", Instant.now(), List.of(), 1, 0));
        queueStatsRegistry.update(new QueueStatsStatusData("node-a", Instant.now(),
                List.of(new QueueStatsStatusData.QueueStat("app.ister.server.MovieFound", 2, 1))));
        recentFailuresBuffer.add(EventFailureStatusData.builder().nodeName("node-a")
                .timestamp(Instant.now()).queue("q").errorMessage("boom").build());
        playbackSessionRegistry.update(playback(UUID.randomUUID()));

        ServerActivitySnapshot snapshot = controller.serverActivitySnapshot(authentication);

        assertEquals(1, snapshot.nodes().size());
        assertEquals(1, snapshot.queueStats().size());
        assertEquals(1, snapshot.recentFailures().size());
        assertEquals(1, snapshot.nowPlaying().size());
    }
}
