package app.ister.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static app.ister.core.config.RabbitReliabilityConfig.DEAD_LETTER_QUEUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceTest {
    @InjectMocks
    private DeadLetterService subject;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private static Message deadLettered(String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-original-exchange", "");
        if (routingKey != null) {
            properties.setHeader("x-original-routingKey", routingKey);
        }
        properties.setHeader("x-exception-message", "boom");
        properties.setHeader("x-exception-stacktrace", "...");
        properties.setHeader("__TypeId__", "app.ister.core.eventdata.MovieFoundData");
        return new Message("{}".getBytes(), properties);
    }

    @Test
    void replaySendsEachMessageBackToItsOriginalQueueWithoutTheFailureHeaders() {
        Message first = deadLettered("app.ister.server.MovieFound");
        Message second = deadLettered("app.ister.server.MediaFileFound.ras3TvVpro");
        doReturn(2).when(rabbitTemplate).execute(any());
        when(rabbitTemplate.receive(DEAD_LETTER_QUEUE)).thenReturn(first, second, null);

        assertEquals(2, subject.replay());

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq("app.ister.server.MovieFound"), sent.capture());
        verify(rabbitTemplate).send(eq(""), eq("app.ister.server.MediaFileFound.ras3TvVpro"), any());
        MessageProperties replayed = sent.getValue().getMessageProperties();
        assertFalse(replayed.getHeaders().containsKey("x-exception-message"));
        assertFalse(replayed.getHeaders().containsKey("x-original-routingKey"));
        assertEquals("app.ister.core.eventdata.MovieFoundData", replayed.getHeader("__TypeId__"));
    }

    @Test
    void replayStopsAtTheCountSeenAtTheStart() {
        doReturn(1).when(rabbitTemplate).execute(any());
        when(rabbitTemplate.receive(DEAD_LETTER_QUEUE)).thenReturn(deadLettered("app.ister.server.MovieFound"));

        assertEquals(1, subject.replay());

        verify(rabbitTemplate).receive(DEAD_LETTER_QUEUE);
    }

    @Test
    void messageWithoutOriginalQueueStaysDeadLettered() {
        doReturn(1).when(rabbitTemplate).execute(any());
        Message orphan = deadLettered(null);
        when(rabbitTemplate.receive(DEAD_LETTER_QUEUE)).thenReturn(orphan);

        assertEquals(0, subject.replay());

        verify(rabbitTemplate).send("", DEAD_LETTER_QUEUE, orphan);
    }
}
