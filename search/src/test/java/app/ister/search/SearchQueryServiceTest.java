package app.ister.search;

import app.ister.core.enums.SearchEntityType;
import app.ister.search.config.TypesenseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchQueryServiceTest {

    @Mock
    private TypesenseClient typesenseClient;

    private SearchQueryService subject;

    @BeforeEach
    void setUp() {
        TypesenseProperties properties = new TypesenseProperties();
        properties.setCollection("media");
        subject = new SearchQueryService(typesenseClient, properties);
    }

    @Test
    void searchMapsHitsToTypedIdsInOrder() {
        UUID movieId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        String response = """
                {"hits": [
                    {"document": {"id": "%s", "type": "MOVIE", "title": "The Matrix"}},
                    {"document": {"id": "%s", "type": "SHOW", "title": "The Matrix Show"}}
                ]}""".formatted(movieId, showId);
        when(typesenseClient.search("media", "matrix", 10, null))
                .thenReturn(JsonMapper.builder().build().readTree(response));

        List<SearchQueryService.SearchHit> hits = subject.search("matrix", 10, null);

        assertEquals(List.of(
                new SearchQueryService.SearchHit(SearchEntityType.MOVIE, movieId),
                new SearchQueryService.SearchHit(SearchEntityType.SHOW, showId)), hits);
    }

    @Test
    void searchReturnsEmptyListWhenNoHits() {
        when(typesenseClient.search(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(JsonMapper.builder().build().readTree("{\"hits\": []}"));

        assertTrue(subject.search("nothing", 10, null).isEmpty());
    }
}
