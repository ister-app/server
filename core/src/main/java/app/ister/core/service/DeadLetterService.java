package app.ister.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import static app.ister.core.config.RabbitReliabilityConfig.DEAD_LETTER_QUEUE;

/**
 * The dead-letter queue holds events whose handler kept failing. Once the cause is fixed (a bug
 * deployed, a feed back online) they can be sent back to the queue they came from instead of
 * being shoveled by hand: {@code RepublishMessageRecoverer} kept that queue in the
 * {@code x-original-*} headers. A replayed message that fails again simply dead-letters again.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeadLetterService {
    static final String ORIGINAL_EXCHANGE = "x-original-exchange";
    static final String ORIGINAL_ROUTING_KEY = "x-original-routingKey";

    private final RabbitTemplate rabbitTemplate;

    /** @return how many messages are waiting in the dead-letter queue */
    public int count() {
        Integer count = rabbitTemplate.execute(channel -> channel.queueDeclarePassive(DEAD_LETTER_QUEUE).getMessageCount());
        return count == null ? 0 : count;
    }

    /**
     * Sends every dead-lettered message back to its original queue.
     *
     * @return the number of messages replayed
     */
    public int replay() {
        int waiting = count();   // bounds the loop: a message that fails again lands back here
        int replayed = 0;
        for (int i = 0; i < waiting; i++) {
            Message message = rabbitTemplate.receive(DEAD_LETTER_QUEUE);
            if (message == null) {
                break;
            }
            MessageProperties properties = message.getMessageProperties();
            String routingKey = properties.getHeader(ORIGINAL_ROUTING_KEY);
            if (routingKey == null || routingKey.isBlank()) {
                log.warn("Dead-lettered message without an original queue; leaving it in place");
                rabbitTemplate.send("", DEAD_LETTER_QUEUE, message);
                continue;
            }
            String exchange = properties.getHeader(ORIGINAL_EXCHANGE);
            properties.getHeaders().keySet().removeIf(key -> key.startsWith("x-exception-") || key.startsWith("x-original-"));
            rabbitTemplate.send(exchange == null ? "" : exchange, routingKey, message);
            replayed++;
        }
        log.info("Replayed {} of {} dead-lettered message(s)", replayed, waiting);
        return replayed;
    }
}
