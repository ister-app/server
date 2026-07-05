package app.ister.search.events;

import app.ister.core.enums.EventType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.SearchIndexRequestedData;
import app.ister.core.eventdata.SearchReindexRequestedData;
import app.ister.search.SearchIndexService;
import app.ister.search.config.TypesenseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HandleSearchIndexRequestedTest {

    @Mock
    private SearchIndexService searchIndexService;

    private TypesenseProperties properties;
    private HandleSearchIndexRequested subject;

    @BeforeEach
    void setUp() {
        properties = new TypesenseProperties();
        properties.setEnabled(true);
        subject = new HandleSearchIndexRequested(searchIndexService, properties);
    }

    @Test
    void handlesSearchIndexRequested() {
        assertEquals(EventType.SEARCH_INDEX_REQUESTED, subject.handles());
    }

    @Test
    void upsertActionCallsUpsert() {
        UUID id = UUID.randomUUID();

        subject.listener(SearchIndexRequestedData.builder()
                .eventType(EventType.SEARCH_INDEX_REQUESTED)
                .entityType(SearchEntityType.MOVIE)
                .entityId(id)
                .action(SearchIndexRequestedData.Action.UPSERT)
                .build());

        verify(searchIndexService).upsert(SearchEntityType.MOVIE, id);
    }

    @Test
    void deleteActionCallsDelete() {
        UUID id = UUID.randomUUID();

        subject.listener(SearchIndexRequestedData.builder()
                .eventType(EventType.SEARCH_INDEX_REQUESTED)
                .entityType(SearchEntityType.TRACK)
                .entityId(id)
                .action(SearchIndexRequestedData.Action.DELETE)
                .build());

        verify(searchIndexService).delete(id);
    }

    @Test
    void rejectsWrongEventType() {
        SearchIndexRequestedData wrongType = SearchIndexRequestedData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .entityType(SearchEntityType.MOVIE)
                .entityId(UUID.randomUUID())
                .action(SearchIndexRequestedData.Action.UPSERT)
                .build();

        assertThrows(IllegalArgumentException.class, () -> subject.listener(wrongType));
    }

    @Test
    void skipsEventWhenTypesenseIsDisabled() {
        properties.setEnabled(false);

        subject.listener(SearchIndexRequestedData.builder()
                .eventType(EventType.SEARCH_INDEX_REQUESTED)
                .entityType(SearchEntityType.MOVIE)
                .entityId(UUID.randomUUID())
                .action(SearchIndexRequestedData.Action.UPSERT)
                .build());

        verifyNoInteractions(searchIndexService);
    }

    @Test
    void reindexHandlerTriggersReindex() {
        HandleSearchReindexRequested reindexHandler = new HandleSearchReindexRequested(searchIndexService, properties);

        reindexHandler.listener(SearchReindexRequestedData.builder()
                .eventType(EventType.SEARCH_REINDEX_REQUESTED)
                .build());

        verify(searchIndexService).reindex();
    }

    @Test
    void reindexHandlerSkipsWhenTypesenseIsDisabled() {
        properties.setEnabled(false);
        HandleSearchReindexRequested reindexHandler = new HandleSearchReindexRequested(searchIndexService, properties);

        reindexHandler.listener(SearchReindexRequestedData.builder()
                .eventType(EventType.SEARCH_REINDEX_REQUESTED)
                .build());

        verifyNoInteractions(searchIndexService);
    }
}
