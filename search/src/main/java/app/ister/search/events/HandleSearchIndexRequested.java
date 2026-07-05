package app.ister.search.events;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.SearchIndexRequestedData;
import app.ister.search.SearchIndexService;
import app.ister.search.config.TypesenseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_SEARCH_INDEX_REQUESTED;

@Service
@Slf4j
@RequiredArgsConstructor
public class HandleSearchIndexRequested implements Handle<SearchIndexRequestedData> {

    private final SearchIndexService searchIndexService;
    private final TypesenseProperties properties;

    @RabbitListener(queues = APP_ISTER_SERVER_SEARCH_INDEX_REQUESTED)
    @Override
    public void listener(SearchIndexRequestedData messageData) {
        Handle.super.listener(messageData);
    }

    @Override
    public EventType handles() {
        return EventType.SEARCH_INDEX_REQUESTED;
    }

    @Override
    public void handle(SearchIndexRequestedData messageData) {
        // If Typesense is not configured, discard the event (same pattern as the TMDB-key check).
        if (!properties.isEnabled()) {
            log.debug("Typesense is disabled, skipping index event for {}", messageData.getEntityId());
            return;
        }
        switch (messageData.getAction()) {
            case UPSERT -> searchIndexService.upsert(messageData.getEntityType(), messageData.getEntityId());
            case DELETE -> searchIndexService.delete(messageData.getEntityId());
        }
    }
}
