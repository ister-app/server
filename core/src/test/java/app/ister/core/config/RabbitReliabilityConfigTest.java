package app.ister.core.config;

import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.service.MessageSender;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RabbitReliabilityConfigTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistry;

    @Mock
    private ObjectProvider<MessageSender> messageSenderProvider;

    @Mock
    private MessageSender messageSender;

    private MessageRecoverer recoverer;

    @BeforeEach
    void setUp() {
        recoverer = new RabbitReliabilityConfig()
                .messageRecoverer(rabbitTemplate, meterRegistry, messageSenderProvider, "test-node");
    }

    private static Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue("app.ister.server.MovieFound");
        properties.setHeader("__TypeId__", "app.ister.core.eventdata.MovieFoundData");
        return new Message(new byte[0], properties);
    }

    private void messageSenderAvailable(MessageSender sender) {
        doAnswer(call -> {
            call.<Consumer<MessageSender>>getArgument(0).accept(sender);
            return null;
        }).when(messageSenderProvider).ifAvailable(any());
    }

    @Test
    void broadcastsFailureAndDeadLetters() {
        messageSenderAvailable(messageSender);
        Exception cause = new ListenerExecutionFailedException("wrapper", new IllegalStateException("boom"), message());

        recoverer.recover(message(), cause);

        ArgumentCaptor<EventFailureStatusData> captor = ArgumentCaptor.forClass(EventFailureStatusData.class);
        verify(messageSender).sendStatus(captor.capture());
        assertEquals("test-node", captor.getValue().getNodeName());
        assertEquals("app.ister.server.MovieFound", captor.getValue().getQueue());
        assertEquals("MovieFoundData", captor.getValue().getEventType());
        assertEquals("boom", captor.getValue().getErrorMessage());
        // Still republished to the dead-letter queue.
        verify(rabbitTemplate).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void deadLettersEvenWhenStatusBroadcastThrows() {
        messageSenderAvailable(messageSender);
        doThrow(new IllegalStateException("rabbit down")).when(messageSender).sendStatus(any());

        recoverer.recover(message(), new RuntimeException("boom"));

        verify(rabbitTemplate).send(anyString(), anyString(), any(Message.class));
    }
}
