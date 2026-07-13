package app.ister.core.status;

import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NodeActivityPublisherTest {

    @Mock
    private MessageSender messageSender;

    private NodeActivityRegistry registry;
    private NodeActivityPublisher subject;

    @BeforeEach
    void setUp() {
        registry = new NodeActivityRegistry();
        subject = new NodeActivityPublisher(registry, messageSender, "node1");
    }

    @Test
    void publishesTheFirstSnapshot() {
        subject.publishIfChanged();

        verify(messageSender).sendStatus(any(NodeActivityStatusData.class));
    }

    @Test
    void doesNotPublishAnUnchangedSnapshot() {
        subject.publishIfChanged();
        subject.publishIfChanged();

        verify(messageSender, times(1)).sendStatus(any(NodeActivityStatusData.class));
    }

    @Test
    void publishesAgainWhenWorkStarts() {
        subject.publishIfChanged();

        registry.started("app.ister.server.MovieFound", "MOVIE_FOUND", Instant.now());
        subject.publishIfChanged();

        verify(messageSender, times(2)).sendStatus(any(NodeActivityStatusData.class));
    }

    @Test
    void publishesAgainWhenTheProcessedCountChanges() {
        long token = registry.started("app.ister.server.MovieFound", "MOVIE_FOUND", Instant.now());
        subject.publishIfChanged();

        registry.finished(token, false);
        subject.publishIfChanged();

        verify(messageSender, times(2)).sendStatus(any(NodeActivityStatusData.class));
    }

    @Test
    void publishesAgainWhenTheFailedCountChanges() {
        long token = registry.started("app.ister.server.MovieFound", "MOVIE_FOUND", Instant.now());
        registry.finished(token, true);

        subject.publishIfChanged();

        verify(messageSender).sendStatus(any(NodeActivityStatusData.class));
    }

    @Test
    void publishesTheSnapshotOfThisNodeWithItsInFlightWork() {
        registry.started("app.ister.server.MovieFound", "MOVIE_FOUND", Instant.now());

        subject.publishIfChanged();

        ArgumentCaptor<NodeActivityStatusData> captor = ArgumentCaptor.forClass(NodeActivityStatusData.class);
        verify(messageSender).sendStatus(captor.capture());
        NodeActivityStatusData snapshot = captor.getValue();
        assertEquals("node1", snapshot.getNodeName());
        assertEquals(1, snapshot.getProcessing().size());
        assertEquals("MOVIE_FOUND", snapshot.getProcessing().getFirst().getEventType());
    }
}
