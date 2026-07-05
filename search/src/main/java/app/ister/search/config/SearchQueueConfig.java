package app.ister.search.config;

import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_SEARCH_INDEX_REQUESTED;
import static app.ister.core.MessageQueue.APP_ISTER_SERVER_SEARCH_REINDEX_REQUESTED;

/**
 * Declares the search queues when Typesense is enabled. When disabled the queues are never
 * declared, so index events are unroutable and dropped by the broker.
 *
 * <p>No {@code JacksonJsonMessageConverter} bean here: the worker module's QueueConfig already
 * provides the single trusted converter, and the server application always includes worker.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.ister.typesense", name = "enabled", havingValue = "true")
public class SearchQueueConfig {

    @Bean
    public Queue queueSearchIndexRequested() {
        return new Queue(APP_ISTER_SERVER_SEARCH_INDEX_REQUESTED);
    }

    @Bean
    public Queue queueSearchReindexRequested() {
        return new Queue(APP_ISTER_SERVER_SEARCH_REINDEX_REQUESTED);
    }
}
