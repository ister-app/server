package app.ister.core.status;

import app.ister.core.eventdata.QueueStatsStatusData;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueDepthPollerTest {

    @Mock
    private AmqpAdmin amqpAdmin;

    @Mock
    private MessageSender messageSender;

    private QueueDepthPoller poller(List<Queue> queues, List<Declarables> declarables) {
        return new QueueDepthPoller(amqpAdmin, messageSender, "node1", queues, declarables);
    }

    @Test
    void publishesStatsForAppQueuesOnly() {
        Declarables declarables = new Declarables(
                new Queue("app.ister.server.MovieFound"),
                new DirectExchange("some.exchange"));
        QueueDepthPoller subject = poller(
                List.of(new Queue("app.ister.server.ShowFound"), new Queue("spring.gen-abc")),
                List.of(declarables));
        when(amqpAdmin.getQueueInfo("app.ister.server.MovieFound"))
                .thenReturn(new QueueInformation("app.ister.server.MovieFound", 3, 1));
        when(amqpAdmin.getQueueInfo("app.ister.server.ShowFound"))
                .thenReturn(new QueueInformation("app.ister.server.ShowFound", 0, 2));

        subject.pollAndPublishIfChanged();

        ArgumentCaptor<QueueStatsStatusData> captor = ArgumentCaptor.forClass(QueueStatsStatusData.class);
        verify(messageSender).sendStatus(captor.capture());
        QueueStatsStatusData data = captor.getValue();
        assertEquals("node1", data.getNodeName());
        assertEquals(2, data.getStats().size());
        // Names are collected in a TreeSet, so MovieFound sorts before ShowFound.
        assertEquals("app.ister.server.MovieFound", data.getStats().getFirst().getQueue());
        assertEquals(3, data.getStats().getFirst().getDepth());
        assertEquals(1, data.getStats().getFirst().getConsumers());
        assertEquals("app.ister.server.ShowFound", data.getStats().getLast().getQueue());
    }

    @Test
    void doesNotPublishTwiceWhenNothingChanged() {
        QueueDepthPoller subject = poller(List.of(new Queue("app.ister.server.ShowFound")), List.of());
        when(amqpAdmin.getQueueInfo("app.ister.server.ShowFound"))
                .thenReturn(new QueueInformation("app.ister.server.ShowFound", 1, 1));

        subject.pollAndPublishIfChanged();
        subject.pollAndPublishIfChanged();

        verify(messageSender, times(1)).sendStatus(any());
    }

    @Test
    void publishesAgainWhenDepthChanged() {
        QueueDepthPoller subject = poller(List.of(new Queue("app.ister.server.ShowFound")), List.of());
        when(amqpAdmin.getQueueInfo("app.ister.server.ShowFound"))
                .thenReturn(new QueueInformation("app.ister.server.ShowFound", 1, 1))
                .thenReturn(new QueueInformation("app.ister.server.ShowFound", 5, 1));

        subject.pollAndPublishIfChanged();
        subject.pollAndPublishIfChanged();

        verify(messageSender, times(2)).sendStatus(any());
    }

    @Test
    void skipsQueuesThatDoNotExist() {
        QueueDepthPoller subject = poller(List.of(new Queue("app.ister.server.ShowFound")), List.of());
        when(amqpAdmin.getQueueInfo("app.ister.server.ShowFound")).thenReturn(null);

        subject.pollAndPublishIfChanged();

        ArgumentCaptor<QueueStatsStatusData> captor = ArgumentCaptor.forClass(QueueStatsStatusData.class);
        verify(messageSender).sendStatus(captor.capture());
        assertEquals(List.of(), captor.getValue().getStats());
    }

    @Test
    void publishesNothingBeforeFirstPoll() {
        poller(List.of(new Queue("app.ister.server.ShowFound")), List.of());

        verifyNoInteractions(messageSender, amqpAdmin);
    }
}
