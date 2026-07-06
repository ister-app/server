package app.ister.core.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate, ObjectProvider<MeterRegistry> meterRegistry) {
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
            republish.recover(message, cause);
        };
    }
}
