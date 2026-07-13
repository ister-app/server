package app.ister.core.status;

import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.PlaybackCommandData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.eventdata.QueueStatsStatusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatusEventListenerTest {

    @InjectMocks
    private StatusEventListener subject;

    @Mock
    private NodeActivityRegistry nodeActivityRegistry;

    @Mock
    private QueueStatsRegistry queueStatsRegistry;

    @Mock
    private RecentFailuresBuffer recentFailuresBuffer;

    @Mock
    private PlaybackSessionRegistry playbackSessionRegistry;

    @Mock
    private ServerStatusBroadcaster broadcaster;

    @Test
    void onNodeActivityUpdatesTheRegistryAndBroadcasts() {
        NodeActivityStatusData data = NodeActivityStatusData.builder().nodeName("node1").build();

        subject.onNodeActivity(data);

        verify(nodeActivityRegistry).updateNode(data);
        verify(broadcaster).emitActivity(data);
        verifyNoInteractions(queueStatsRegistry, recentFailuresBuffer, playbackSessionRegistry);
    }

    @Test
    void onQueueStatsUpdatesTheRegistryAndBroadcasts() {
        QueueStatsStatusData data = QueueStatsStatusData.builder().nodeName("node1").build();

        subject.onQueueStats(data);

        verify(queueStatsRegistry).update(data);
        verify(broadcaster).emitActivity(data);
        verifyNoInteractions(nodeActivityRegistry, recentFailuresBuffer, playbackSessionRegistry);
    }

    @Test
    void onFailureBuffersTheFailureAndBroadcasts() {
        EventFailureStatusData data = EventFailureStatusData.builder().nodeName("node1").build();

        subject.onFailure(data);

        verify(recentFailuresBuffer).add(data);
        verify(broadcaster).emitActivity(data);
        verifyNoInteractions(nodeActivityRegistry, queueStatsRegistry, playbackSessionRegistry);
    }

    @Test
    void onPlaybackUpdatesTheSessionRegistryAndBroadcastsTheWholeSnapshot() {
        PlaybackStatusData data = PlaybackStatusData.builder().nodeName("node1").build();
        List<PlaybackStatusData> snapshot = List.of(data);
        when(playbackSessionRegistry.snapshot()).thenReturn(snapshot);

        subject.onPlayback(data);

        verify(playbackSessionRegistry).update(data);
        verify(broadcaster).emitNowPlaying(snapshot);
    }

    @Test
    void onPlaybackCommandOnlyBroadcasts() {
        PlaybackCommandData data = PlaybackCommandData.builder().build();

        subject.onPlaybackCommand(data);

        verify(broadcaster).emitCommand(data);
        verifyNoInteractions(nodeActivityRegistry, queueStatsRegistry, recentFailuresBuffer, playbackSessionRegistry);
    }
}
