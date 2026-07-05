package app.ister.search.events;

import app.ister.core.enums.EventType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.SearchIndexRequestedData;
import app.ister.core.eventdata.SearchReindexRequestedData;
import app.ister.search.SearchIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HandleSearchIndexRequestedTest {

    @Mock
    private SearchIndexService searchIndexService;

    @InjectMocks
    private HandleSearchIndexRequested subject;

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
    void reindexHandlerTriggersReindex() {
        HandleSearchReindexRequested reindexHandler = new HandleSearchReindexRequested(searchIndexService);

        reindexHandler.listener(SearchReindexRequestedData.builder()
                .eventType(EventType.SEARCH_REINDEX_REQUESTED)
                .build());

        verify(searchIndexService).reindex();
    }
}
