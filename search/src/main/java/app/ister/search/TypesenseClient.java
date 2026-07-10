package app.ister.search;

import app.ister.core.config.LanguageProperties;
import app.ister.search.config.TypesenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
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
@RegisterReflectionForBinding(SearchDocument.class) // Jackson binding in the GraalVM native image
public class TypesenseClient {

    /** Language-independent fields, shared across all documents. */
    private static final String BASE_FIELDS = """
            {"name": "type",         "type": "string", "facet": true},
            {"name": "title",        "type": "string", "sort": true},
            {"name": "context",      "type": "string", "optional": true},
            {"name": "year",         "type": "int32",  "optional": true},
            {"name": "number",       "type": "int32",  "optional": true},
            {"name": "seasonNumber", "type": "int32",  "optional": true},
            {"name": "libraryId",    "type": "string", "optional": true, "facet": true}""";

    private final RestClient restClient;
    private final LanguageProperties languageProperties;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public TypesenseClient(TypesenseProperties properties,
                           LanguageProperties languageProperties,
                           RestClient.Builder restClientBuilder) {
        this.languageProperties = languageProperties;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-TYPESENSE-API-KEY", properties.getApiKey())
                .build();
    }

    public void createCollection(String name) {
        restClient.post()
                .uri("/collections")
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildSchema(name))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Builds the collection schema: the language-independent {@link #BASE_FIELDS} plus, for each
     * configured language, {@code title_<tag>}/{@code description_<tag>}/{@code genre_<tag>} string
     * fields carrying the matching Typesense {@code locale} so tokenization is language-aware.
     */
    private String buildSchema(String name) {
        List<String> fields = new ArrayList<>();
        fields.add(BASE_FIELDS);
        for (String tag : languageProperties.tags()) {
            for (String base : List.of("title", "description", "genre")) {
                fields.add("{\"name\": \"%s_%s\", \"type\": \"string\", \"optional\": true, \"locale\": \"%s\"}"
                        .formatted(base, tag, tag));
            }
        }
        return "{\"name\": \"%s\", \"fields\": [%s]}".formatted(name, String.join(",\n", fields));
    }

    public Optional<String> getAliasTarget(String alias) {
        try {
            JsonNode response = restClient.get()
                    .uri("/aliases/{alias}", alias)
                    .retrieve()
                    .body(JsonNode.class);
            return Optional.ofNullable(response).map(node -> node.path("collection_name").asString());
        } catch (HttpClientErrorException.NotFound _) {
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
        } catch (HttpClientErrorException.NotFound _) {
            log.debug("Document {} not present in collection {}; nothing to delete", documentId, collection);
        }
    }

    public JsonNode search(String collection, String term, int perPage, UUID libraryId) {
        String queryBy = queryByFields();
        String queryByWeights = queryByWeights();
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/collections/{collection}/documents/search")
                            .queryParam("q", term)
                            .queryParam("query_by", queryBy)
                            .queryParam("query_by_weights", queryByWeights)
                            .queryParam("per_page", perPage);
                    if (libraryId != null) {
                        uriBuilder.queryParam("filter_by", "libraryId:=" + libraryId);
                    }
                    return uriBuilder.build(collection);
                })
                .retrieve()
                .body(JsonNode.class);
    }

    /** {@code title,context} plus every configured language's title/description/genre field. */
    private String queryByFields() {
        List<String> fields = new ArrayList<>(List.of("title", "context"));
        for (String tag : languageProperties.tags()) {
            fields.add("title_" + tag);
            fields.add("description_" + tag);
            fields.add("genre_" + tag);
        }
        return String.join(",", fields);
    }

    /** Weights aligned with {@link #queryByFields()}: titles rank highest, descriptions/genres lowest. */
    private String queryByWeights() {
        List<String> weights = new ArrayList<>(List.of("8", "4"));
        for (int i = 0; i < languageProperties.tags().size(); i++) {
            weights.add("5"); // title_<tag>
            weights.add("1"); // description_<tag>
            weights.add("1"); // genre_<tag>
        }
        return String.join(",", weights);
    }
}
