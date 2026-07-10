package app.ister.core.config;

import org.springframework.amqp.core.Base64UrlNamingStrategy;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cluster-wide status fan-out: every node publishes its live status here (in-flight work,
 * queue depths, failures, playback heartbeats) and every node consumes it — including its
 * own messages — into in-memory registries, so all nodes converge on the same view and a
 * client can subscribe to any node. The per-node queue is anonymous and auto-deletes, so
 * status traffic never piles up for a node that is down.
 */
@Configuration
public class StatusExchangeConfig {

    public static final String STATUS_EXCHANGE = "app.ister.server.status";

    @Bean
    public FanoutExchange statusExchange() {
        return new FanoutExchange(STATUS_EXCHANGE);
    }

    /**
     * Like AnonymousQueue (unique name, exclusive, auto-delete) but without the
     * x-queue-leader-locator argument, which RabbitMQ 3 rejects on classic queues.
     */
    @Bean
    public Queue statusQueue() {
        return new Queue(new Base64UrlNamingStrategy("ister.status.").generateName(), false, true, true);
    }

    @Bean
    public Binding statusBinding(FanoutExchange statusExchange, Queue statusQueue) {
        return BindingBuilder.bind(statusQueue).to(statusExchange);
    }
}
