package app.ister.core.status;

import app.ister.core.enums.PlaybackCommandType;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.PlaybackCommandData;
import app.ister.core.eventdata.PlaybackStatusData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerStatusBroadcasterTest {

    private ServerStatusBroadcaster subject;

    @BeforeEach
    void setUp() {
        subject = new ServerStatusBroadcaster();
    }

    @Test
    void nowPlayingIsSeededWithAnEmptyListForFreshSubscribers() {
        List<PlaybackStatusData> first = subject.nowPlayingFlux().blockFirst(Duration.ofSeconds(1));

        assertEquals(List.of(), first);
    }

    @Test
    void activityReplaysTheLatestValueToALateSubscriber() {
        NodeActivityStatusData data = NodeActivityStatusData.builder().nodeName("node1").build();

        subject.emitActivity(data);

        assertSame(data, subject.activityFlux().blockFirst(Duration.ofSeconds(1)));
    }

    @Test
    void nowPlayingReplaysTheLatestSessionList() {
        List<PlaybackStatusData> sessions = List.of(PlaybackStatusData.builder().nodeName("node1").build());

        subject.emitNowPlaying(sessions);

        assertSame(sessions, subject.nowPlayingFlux().blockFirst(Duration.ofSeconds(1)));
    }

    /**
     * Commands must not replay: a (re)subscriber would otherwise re-execute the last command.
     */
    @Test
    void commandsReachOnlyLiveSubscribers() {
        PlaybackCommandData dropped = PlaybackCommandData.builder()
                .playQueueId(UUID.randomUUID())
                .command(PlaybackCommandType.PAUSE)
                .build();
        subject.emitCommand(dropped);

        List<PlaybackCommandData> received = new CopyOnWriteArrayList<>();
        subject.commandFlux().subscribe(received::add);

        PlaybackCommandData delivered = PlaybackCommandData.builder()
                .playQueueId(UUID.randomUUID())
                .command(PlaybackCommandType.PLAY)
                .build();
        subject.emitCommand(delivered);

        assertEquals(List.of(delivered), received);
    }

    @Test
    void activityKeepsFeedingAnAttachedSubscriber() {
        List<Object> received = new CopyOnWriteArrayList<>();
        subject.activityFlux().subscribe(received::add);

        subject.emitActivity(NodeActivityStatusData.builder().nodeName("node1").build());
        subject.emitActivity(NodeActivityStatusData.builder().nodeName("node2").build());

        assertEquals(2, received.size());
        assertTrue(received.getLast() instanceof NodeActivityStatusData);
    }
}
