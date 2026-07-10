package app.ister.api.controller;

import app.ister.api.dto.PlaybackSession;
import app.ister.api.dto.ServerActivityEvent;
import app.ister.api.dto.ServerActivitySnapshot;
import app.ister.core.enums.PlayState;
import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.eventdata.QueueStatsStatusData;
import app.ister.core.status.NodeActivityRegistry;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.QueueStatsRegistry;
import app.ister.core.status.RecentFailuresBuffer;
import app.ister.core.status.ServerStatusBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ServerStatusControllerTest {

    private ServerStatusBroadcaster broadcaster;
    private NodeActivityRegistry nodeActivityRegistry;
    private QueueStatsRegistry queueStatsRegistry;
    private RecentFailuresBuffer recentFailuresBuffer;
    private PlaybackSessionRegistry playbackSessionRegistry;
    private ServerStatusController controller;

    @BeforeEach
    void setUp() {
        broadcaster = new ServerStatusBroadcaster();
        nodeActivityRegistry = new NodeActivityRegistry();
        queueStatsRegistry = new QueueStatsRegistry();
        recentFailuresBuffer = new RecentFailuresBuffer();
        playbackSessionRegistry = new PlaybackSessionRegistry();
        controller = new ServerStatusController(broadcaster, nodeActivityRegistry, queueStatsRegistry,
                recentFailuresBuffer, playbackSessionRegistry);
    }

    private static PlaybackStatusData playback(UUID playQueueId) {
        return PlaybackStatusData.builder()
                .playQueueId(playQueueId)
                .userId(UUID.randomUUID())
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
    void nowPlayingStartsWithCurrentSnapshotThenUpdates() {
        UUID first = UUID.randomUUID();
        playbackSessionRegistry.update(playback(first));

        List<List<PlaybackSession>> received = new CopyOnWriteArrayList<>();
        controller.nowPlaying().subscribe(received::add);

        UUID second = UUID.randomUUID();
        playbackSessionRegistry.update(playback(second));
        broadcaster.emitNowPlaying(playbackSessionRegistry.snapshot());

        assertEquals(2, received.size());
        assertEquals(1, received.get(0).size());
        assertEquals(first, received.get(0).getFirst().playQueueId());
        assertEquals(2, received.get(1).size());
    }

    @Test
    void snapshotAggregatesAllRegistries() {
        nodeActivityRegistry.updateNode(new NodeActivityStatusData("node-a", Instant.now(), List.of(), 1, 0));
        queueStatsRegistry.update(new QueueStatsStatusData("node-a", Instant.now(),
                List.of(new QueueStatsStatusData.QueueStat("app.ister.server.MovieFound", 2, 1))));
        recentFailuresBuffer.add(EventFailureStatusData.builder().nodeName("node-a")
                .timestamp(Instant.now()).queue("q").errorMessage("boom").build());
        playbackSessionRegistry.update(playback(UUID.randomUUID()));

        ServerActivitySnapshot snapshot = controller.serverActivitySnapshot();

        assertEquals(1, snapshot.nodes().size());
        assertEquals(1, snapshot.queueStats().size());
        assertEquals(1, snapshot.recentFailures().size());
        assertEquals(1, snapshot.nowPlaying().size());
    }
}
