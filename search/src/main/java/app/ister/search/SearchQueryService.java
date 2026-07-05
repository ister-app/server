package app.ister.search;

import app.ister.core.enums.SearchEntityType;
import app.ister.search.config.TypesenseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ister.typesense", name = "enabled", havingValue = "true")
public class SearchQueryService {

    private final TypesenseClient typesenseClient;
    private final TypesenseProperties properties;

    /** A search hit in Typesense relevance order; the entity itself lives in PostgreSQL. */
    public record SearchHit(SearchEntityType entityType, UUID entityId) {
    }

    public List<SearchHit> search(String term, int size, UUID libraryId) {
        JsonNode response = typesenseClient.search(properties.getCollection(), term, size, libraryId);
        List<SearchHit> hits = new ArrayList<>();
        if (response != null) {
            for (JsonNode hit : response.path("hits")) {
                JsonNode document = hit.path("document");
                hits.add(new SearchHit(
                        SearchEntityType.valueOf(document.path("type").asString()),
                        UUID.fromString(document.path("id").asString())));
            }
        }
        return hits;
    }
}
