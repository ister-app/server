package app.ister.search;

import app.ister.core.config.LanguageProperties;
import app.ister.search.config.TypesenseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TypesenseClientTest {

    private MockRestServiceServer server;
    private TypesenseClient subject;

    @BeforeEach
    void setUp() {
        TypesenseProperties properties = new TypesenseProperties();
        properties.setHost("localhost");
        properties.setPort(8108);
        properties.setProtocol("http");
        properties.setApiKey("test-key");
        properties.setCollection("media");

        LanguageProperties languageProperties = new LanguageProperties();
        languageProperties.setLanguages(List.of("en", "nl"));

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        subject = new TypesenseClient(properties, languageProperties, builder);
    }

    @Test
    void searchSendsQueryParametersAndApiKey() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/collections/media/documents/search?q=matrix")))
                .andExpect(header("X-TYPESENSE-API-KEY", "test-key"))
                .andExpect(queryParam("query_by",
                        "title,context,title_en,description_en,genre_en,title_nl,description_nl,genre_nl"))
                .andExpect(queryParam("query_by_weights", "8,4,5,1,1,5,1,1"))
                .andExpect(queryParam("per_page", "5"))
                .andRespond(withSuccess("""
                        {"hits": [{"document": {"id": "abc", "type": "MOVIE"}}]}
                        """, MediaType.APPLICATION_JSON));

        JsonNode response = subject.search("media", "matrix", 5, null);

        assertEquals("abc", response.path("hits").get(0).path("document").path("id").asString());
        server.verify();
    }

    @Test
    void createCollectionBuildsPerLanguageLocaleFields() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/collections")))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"name\": \"media_v1\""),
                        org.hamcrest.Matchers.containsString("\"description_en\""),
                        org.hamcrest.Matchers.containsString("\"description_nl\""),
                        org.hamcrest.Matchers.containsString("\"title_en\""),
                        org.hamcrest.Matchers.containsString("\"genre_nl\""),
                        org.hamcrest.Matchers.containsString("\"locale\": \"nl\""))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        subject.createCollection("media_v1");

        server.verify();
    }

    @Test
    void searchWithLibraryIdAddsFilter() {
        UUID libraryId = UUID.randomUUID();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("filter_by=libraryId:%3D" + libraryId)))
                .andRespond(withSuccess("{\"hits\": []}", MediaType.APPLICATION_JSON));

        subject.search("media", "matrix", 5, libraryId);

        server.verify();
    }

    @Test
    void importDocumentsSendsJsonLines() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/collections/media_v1/documents/import?action=upsert")))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"id\":\"1\""),
                        org.hamcrest.Matchers.containsString("\n"),
                        org.hamcrest.Matchers.containsString("\"id\":\"2\""))))
                .andRespond(withSuccess("{\"success\":true}\n{\"success\":true}", MediaType.TEXT_PLAIN));

        subject.importDocuments("media_v1", List.of(
                SearchDocument.builder().id("1").type("MOVIE").title("One").build(),
                SearchDocument.builder().id("2").type("SHOW").title("Two").build()));

        server.verify();
    }

    @Test
    void importDocumentsOmitsNullFields() {
        server.expect(anything())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))))
                .andRespond(withSuccess("{\"success\":true}", MediaType.TEXT_PLAIN));

        subject.importDocuments("media_v1", List.of(SearchDocument.builder().id("1").type("MOVIE").title("One").build()));

        server.verify();
    }

    @Test
    void deleteDocumentIgnoresNotFound() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/collections/media/documents/gone")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertDoesNotThrow(() -> subject.deleteDocument("media", "gone"));
        server.verify();
    }

    @Test
    void getAliasTargetReturnsEmptyOnNotFound() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/aliases/media")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertEquals(Optional.empty(), subject.getAliasTarget("media"));
    }

    @Test
    void getAliasTargetReturnsCollectionName() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/aliases/media")))
                .andRespond(withSuccess("{\"name\": \"media\", \"collection_name\": \"media_v1\"}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.of("media_v1"), subject.getAliasTarget("media"));
    }

    @Test
    void listCollectionNamesParsesNames() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/collections")))
                .andRespond(withSuccess("[{\"name\": \"media_v1\"}, {\"name\": \"media_v2\"}]", MediaType.APPLICATION_JSON));

        assertEquals(List.of("media_v1", "media_v2"), subject.listCollectionNames());
    }
}
