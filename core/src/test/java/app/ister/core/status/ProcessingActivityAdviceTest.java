package app.ister.core.status;

import app.ister.core.config.RabbitReliabilityConfig;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessingActivityAdviceTest {

    @Mock
    private MethodInvocation invocation;

    private NodeActivityRegistry registry;
    private Queue statusQueue;
    private ProcessingActivityAdvice advice;

    @BeforeEach
    void setUp() {
        registry = new NodeActivityRegistry();
        statusQueue = new Queue("ister.status.test", false, true, true);
        advice = new ProcessingActivityAdvice(registry, statusQueue);
    }

    private static Message message(String consumerQueue, String typeId) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(consumerQueue);
        if (typeId != null) {
            properties.setHeader("__TypeId__", typeId);
        }
        return new Message(new byte[0], properties);
    }

    @Test
    void countsSuccessfulDelivery() throws Throwable {
        when(invocation.getArguments()).thenReturn(new Object[]{
                message("app.ister.server.MovieFound", "app.ister.core.eventdata.MovieFoundData")});
        when(invocation.proceed()).thenAnswer(call -> {
            // While the listener runs, the delivery must be visible as in-flight work.
            var processing = registry.localSnapshot("node", Instant.now()).getProcessing();
            assertEquals(1, processing.size());
            assertEquals("app.ister.server.MovieFound", processing.getFirst().getQueue());
            assertEquals("MovieFoundData", processing.getFirst().getEventType());
            return null;
        });

        advice.invoke(invocation);

        var snapshot = registry.localSnapshot("node", Instant.now());
        assertTrue(snapshot.getProcessing().isEmpty());
        assertEquals(1, snapshot.getProcessedCount());
        assertEquals(0, snapshot.getFailedCount());
    }

    @Test
    void countsFailedDeliveryAndRethrows() throws Throwable {
        when(invocation.getArguments()).thenReturn(new Object[]{
                message("app.ister.server.MovieFound", "app.ister.core.eventdata.MovieFoundData")});
        when(invocation.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> advice.invoke(invocation));

        var snapshot = registry.localSnapshot("node", Instant.now());
        assertTrue(snapshot.getProcessing().isEmpty());
        assertEquals(0, snapshot.getProcessedCount());
        assertEquals(1, snapshot.getFailedCount());
    }

    @Test
    void skipsStatusQueue() throws Throwable {
        when(invocation.getArguments()).thenReturn(new Object[]{message(statusQueue.getName(), null)});

        advice.invoke(invocation);

        var snapshot = registry.localSnapshot("node", Instant.now());
        assertEquals(0, snapshot.getProcessedCount());
        assertEquals(0, snapshot.getFailedCount());
    }

    @Test
    void skipsDeadLetterQueue() throws Throwable {
        when(invocation.getArguments()).thenReturn(new Object[]{
                message(RabbitReliabilityConfig.DEAD_LETTER_QUEUE, null)});

        advice.invoke(invocation);

        assertEquals(0, registry.localSnapshot("node", Instant.now()).getProcessedCount());
    }

    @Test
    void proceedsWhenNoMessageArgument() throws Throwable {
        when(invocation.getArguments()).thenReturn(new Object[]{"not a message"});

        advice.invoke(invocation);

        verify(invocation).proceed();
        assertEquals(0, registry.localSnapshot("node", Instant.now()).getProcessedCount());
    }
}
