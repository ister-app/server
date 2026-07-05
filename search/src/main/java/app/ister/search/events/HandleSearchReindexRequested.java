package app.ister.search.events;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.SearchReindexRequestedData;
import app.ister.search.SearchIndexService;
import app.ister.search.config.TypesenseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_SEARCH_REINDEX_REQUESTED;

@Service
@Slf4j
@RequiredArgsConstructor
public class HandleSearchReindexRequested implements Handle<SearchReindexRequestedData> {

    private final SearchIndexService searchIndexService;
    private final TypesenseProperties properties;

    @RabbitListener(queues = APP_ISTER_SERVER_SEARCH_REINDEX_REQUESTED)
    @Override
    public void listener(SearchReindexRequestedData messageData) {
        Handle.super.listener(messageData);
    }

    @Override
    public EventType handles() {
        return EventType.SEARCH_REINDEX_REQUESTED;
    }

    @Override
    public void handle(SearchReindexRequestedData messageData) {
        if (!properties.isEnabled()) {
            log.debug("Typesense is disabled, skipping reindex event");
            return;
        }
        searchIndexService.reindex();
    }
}
