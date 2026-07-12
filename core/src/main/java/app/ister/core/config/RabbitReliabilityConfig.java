package app.ister.core.config;

import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.service.MessageSender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * Failed event handling: listener retry is configured in core.properties
 * (spring.rabbitmq.listener.simple.retry.*); once retries are exhausted the message is
 * republished here instead of being dropped, with the exception preserved in the
 * x-exception-* headers. Messages in the dead-letter queue can be inspected and shoveled
 * back to their original queue (stored in the x-original-* headers) after the cause is fixed.
 */
@Configuration
@Slf4j
public class RabbitReliabilityConfig {

    public static final String DEAD_LETTER_QUEUE = "app.ister.server.dead-letter";

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DEAD_LETTER_QUEUE);
    }

    /**
     * All events are published to the default exchange with the queue name as the routing key, so a
     * message for a queue that no node declared is silently dropped by the broker. With mandatory
     * publishing (spring.rabbitmq.template.mandatory) the broker returns those messages here, which
     * turns a routing bug into a loud log line instead of an event that just never happens.
     */
    @Bean
    public RabbitTemplateCustomizer unroutableMessageLogger(ObjectProvider<MeterRegistry> meterRegistry) {
        return template -> template.setReturnsCallback(returned -> {
            String queue = returned.getRoutingKey();
            log.error("Message returned as unroutable: no queue {} exists (replyCode={}, replyText={}). "
                            + "The event is lost; check the queue naming/declaration config for this directory or node.",
                    queue, returned.getReplyCode(), returned.getReplyText());
            meterRegistry.ifAvailable(registry -> Counter
                    .builder("ister.events.unroutable")
                    .description("Messages returned by the broker because no queue was bound to the routing key")
                    .tag("queue", queue)
                    .register(registry)
                    .increment());
        });
    }

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate, ObjectProvider<MeterRegistry> meterRegistry,
                                             ObjectProvider<MessageSender> messageSender,
                                             @Value("${app.ister.server.name}") String nodeName) {
        RepublishMessageRecoverer republish = new RepublishMessageRecoverer(rabbitTemplate, "", DEAD_LETTER_QUEUE);
        return (message, cause) -> {
            String queue = String.valueOf(message.getMessageProperties().getConsumerQueue());
            // The x-exception-* headers preserve the cause on the dead-lettered message, but log
            // it here too: without this the actual failure is invisible in the application logs
            // (RepublishMessageRecoverer only logs a WARN without the stack trace).
            log.error("Dead-lettering message from queue {} after exhausted retries", queue, cause);
            meterRegistry.ifAvailable(registry -> Counter
                    .builder("ister.events.dead.lettered")
                    .description("Messages republished to the dead-letter queue after exhausted retries")
                    .tag("queue", queue)
                    .register(registry)
                    .increment());
            broadcastFailure(messageSender, nodeName, message, queue, cause);
            republish.recover(message, cause);
        };
    }

    /** Live visibility on the status exchange; must never prevent dead-lettering. */
    private static void broadcastFailure(ObjectProvider<MessageSender> messageSender, String nodeName,
                                         org.springframework.amqp.core.Message message, String queue, Throwable cause) {
        try {
            Object typeId = message.getMessageProperties().getHeader("__TypeId__");
            // The listener wraps the handler exception in a ListenerExecutionFailedException.
            Throwable rootCause = cause.getCause() != null ? cause.getCause() : cause;
            messageSender.ifAvailable(sender -> sender.sendStatus(EventFailureStatusData.builder()
                    .nodeName(nodeName)
                    .timestamp(Instant.now())
                    .queue(queue)
                    .eventType(typeId == null ? "unknown" : typeId.toString().substring(typeId.toString().lastIndexOf('.') + 1))
                    .errorMessage(rootCause.getMessage())
                    .build()));
        } catch (RuntimeException e) {
            log.warn("Could not broadcast failure status for queue {}", queue, e);
        }
    }
}
