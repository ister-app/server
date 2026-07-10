package app.ister.core.status;

import app.ister.core.config.RabbitReliabilityConfig;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Container-level advice that records every message delivery in the
 * NodeActivityRegistry while it is being handled. Appended to the listener
 * container advice chain after Boot's retry interceptor (see
 * RabbitInstrumentationConfig), so each retry attempt shows up as in-flight work.
 * Skips the status and dead-letter queues: status handling must not report itself.
 */
@Component
public class ProcessingActivityAdvice implements MethodInterceptor {

    private static final String TYPE_ID_HEADER = "__TypeId__";

    private final NodeActivityRegistry registry;
    private final String statusQueueName;

    public ProcessingActivityAdvice(NodeActivityRegistry registry, @Qualifier("statusQueue") Queue statusQueue) {
        this.registry = registry;
        this.statusQueueName = statusQueue.getName();
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Message message = findMessage(invocation.getArguments());
        if (message == null) {
            return invocation.proceed();
        }
        String queue = message.getMessageProperties().getConsumerQueue();
        if (queue == null || queue.equals(statusQueueName) || queue.equals(RabbitReliabilityConfig.DEAD_LETTER_QUEUE)) {
            return invocation.proceed();
        }
        long token = registry.started(queue, eventTypeOf(message), Instant.now());
        boolean failed = true;
        try {
            Object result = invocation.proceed();
            failed = false;
            return result;
        } finally {
            registry.finished(token, failed);
        }
    }

    private static Message findMessage(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof Message message) {
                return message;
            }
        }
        return null;
    }

    /** Human-readable event type from the Jackson type header, e.g. "MovieFoundData". */
    private static String eventTypeOf(Message message) {
        Object typeId = message.getMessageProperties().getHeader(TYPE_ID_HEADER);
        if (typeId == null) {
            return "unknown";
        }
        String name = typeId.toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
