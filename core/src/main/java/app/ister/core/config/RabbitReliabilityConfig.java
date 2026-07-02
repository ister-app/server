package app.ister.core.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
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
public class RabbitReliabilityConfig {

    public static final String DEAD_LETTER_QUEUE = "app.ister.server.dead-letter";

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DEAD_LETTER_QUEUE);
    }

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, "", DEAD_LETTER_QUEUE);
    }
}
