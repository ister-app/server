package app.ister.search;

import app.ister.search.config.TypesenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Thin wrapper over the Typesense REST API. Document and search calls accept the alias name;
 * Typesense resolves aliases transparently. Errors surface as unchecked
 * {@link org.springframework.web.client.RestClientException}s so failed event handling is
 * retried and eventually dead-lettered by the existing RabbitMQ setup.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "app.ister.typesense", name = "enabled", havingValue = "true")
public class TypesenseClient {

    private static final String SCHEMA_TEMPLATE = """
            {
              "name": "%s",
              "fields": [
                {"name": "type",         "type": "string", "facet": true},
                {"name": "title",        "type": "string", "sort": true},
                {"name": "context",      "type": "string", "optional": true},
                {"name": "description",  "type": "string", "optional": true},
                {"name": "genre",        "type": "string", "optional": true},
                {"name": "year",         "type": "int32",  "optional": true},
                {"name": "number",       "type": "int32",  "optional": true},
                {"name": "seasonNumber", "type": "int32",  "optional": true},
                {"name": "libraryId",    "type": "string", "optional": true, "facet": true}
              ]
            }""";

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public TypesenseClient(TypesenseProperties properties, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-TYPESENSE-API-KEY", properties.getApiKey())
                .build();
    }

    public void createCollection(String name) {
        restClient.post()
                .uri("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .body(SCHEMA_TEMPLATE.formatted(name))
                .retrieve()
                .toBodilessEntity();
    }

    public Optional<String> getAliasTarget(String alias) {
        try {
            JsonNode response = restClient.get()
                    .uri("/aliases/{alias}", alias)
                    .retrieve()
                    .body(JsonNode.class);
            return Optional.ofNullable(response).map(node -> node.path("collection_name").asString());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public void upsertAlias(String alias, String collectionName) {
        restClient.put()
                .uri("/aliases/{alias}", alias)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"collection_name\": \"%s\"}".formatted(collectionName))
                .retrieve()
                .toBodilessEntity();
    }

    public List<String> listCollectionNames() {
        JsonNode response = restClient.get()
                .uri("/collections")
                .retrieve()
                .body(JsonNode.class);
        List<String> names = new ArrayList<>();
        if (response != null) {
            response.forEach(collection -> names.add(collection.path("name").asString()));
        }
        return names;
    }

    public void dropCollection(String name) {
        restClient.delete()
                .uri("/collections/{name}", name)
                .retrieve()
                .toBodilessEntity();
    }

    public void upsertDocument(String collection, SearchDocument document) {
        restClient.post()
                .uri("/collections/{collection}/documents?action=upsert", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(document)
                .retrieve()
                .toBodilessEntity();
    }

    public void importDocuments(String collection, List<SearchDocument> documents) {
        String jsonLines = documents.stream()
                .map(jsonMapper::writeValueAsString)
                .collect(Collectors.joining("\n"));
        // The import endpoint returns 200 with a per-line result; failed lines are reported in the body.
        String response = restClient.post()
                .uri("/collections/{collection}/documents/import?action=upsert", collection)
                .contentType(MediaType.TEXT_PLAIN)
                .body(jsonLines)
                .retrieve()
                .body(String.class);
        if (response != null && response.contains("\"success\":false")) {
            log.warn("Some documents failed to import into collection {}: {}", collection, response);
        }
    }

    public void deleteDocument(String collection, String documentId) {
        try {
            restClient.delete()
                    .uri("/collections/{collection}/documents/{id}", collection, documentId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Document {} not present in collection {}; nothing to delete", documentId, collection);
        }
    }

    public JsonNode search(String collection, String term, int perPage, UUID libraryId) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/collections/{collection}/documents/search")
                            .queryParam("q", term)
                            .queryParam("query_by", "title,context,description,genre")
                            .queryParam("query_by_weights", "8,4,1,1")
                            .queryParam("per_page", perPage);
                    if (libraryId != null) {
                        uriBuilder.queryParam("filter_by", "libraryId:=" + libraryId);
                    }
                    return uriBuilder.build(collection);
                })
                .retrieve()
                .body(JsonNode.class);
    }
}
