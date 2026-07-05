package app.ister.search.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_SEARCH_INDEX_REQUESTED;
import static app.ister.core.MessageQueue.APP_ISTER_SERVER_SEARCH_REINDEX_REQUESTED;

/**
 * Declares the search queues. The queues exist regardless of the enabled flag (bean conditions
 * are frozen at native-image build time); when Typesense is disabled the handlers consume and
 * discard the events, so no backlog builds up.
 *
 * <p>No {@code JacksonJsonMessageConverter} bean here: the worker module's QueueConfig already
 * provides the single trusted converter, and the server application always includes worker.
 */
@Configuration
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
