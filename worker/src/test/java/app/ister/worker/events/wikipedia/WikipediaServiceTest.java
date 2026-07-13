package app.ister.worker.events.wikipedia;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WikipediaServiceTest {

    private static final String WIKIDATA_ENDPOINT = "https://www.wikidata.org/wiki/Special:EntityData/";
    private static final String WIKIDATA_API = "https://www.wikidata.org/w/api.php";
    private static final String EN_SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/";
    private static final String NL_SUMMARY = "https://nl.wikipedia.org/api/rest_v1/page/summary/";
    private static final List<String> EN_NL = List.of("en", "nl");

    private WikipediaService subject;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        subject = new WikipediaService(WIKIDATA_ENDPOINT, WIKIDATA_API);
        // The service builds its own RestClient in the constructor; rebind it to a mock server
        // so no real network calls are made.
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(subject, "restClient", builder.build());
    }

    @Test
    void returnsABioPerLanguageAndTheFirstThumbnail() {
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q1.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q1":{"sitelinks":{
                          "enwiki":{"title":"Kate Bush"},
                          "nlwiki":{"title":"Kate Bush (zangeres)"}}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EN_SUMMARY + "Kate%20Bush"))
                .andRespond(withSuccess("""
                        {"extract":"English singer.","thumbnail":{"source":"https://wiki/kate.jpg"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(NL_SUMMARY + "Kate%20Bush")))
                .andRespond(withSuccess("{\"extract\":\"Engelse zangeres.\"}", MediaType.APPLICATION_JSON));

        WikipediaService.Content content = subject.fetchContent("Q1", EN_NL);

        assertEquals("English singer.", content.bios().get("en"));
        assertEquals("Engelse zangeres.", content.bios().get("nl"));
        assertEquals("https://wiki/kate.jpg", content.thumbnail());
        server.verify();
    }

    @Test
    void skipsLanguagesTheEntityHasNoWikiFor() {
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q1.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q1":{"sitelinks":{"enwiki":{"title":"Kate Bush"}}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EN_SUMMARY + "Kate%20Bush"))
                .andRespond(withSuccess("{\"extract\":\"English singer.\"}", MediaType.APPLICATION_JSON));

        WikipediaService.Content content = subject.fetchContent("Q1", EN_NL);

        assertEquals(1, content.bios().size());
        assertNull(content.thumbnail());
        server.verify();
    }

    @Test
    void returnsEmptyWithoutAWikidataId() {
        assertTrue(subject.fetchContent(null, EN_NL).bios().isEmpty());
        server.verify();
    }

    // ========== fetchContentForPerson ==========

    @Test
    void findsAPersonByNameAndSkipsNonHumanAndSameNamedCandidates() {
        server.expect(requestTo(startsWith(WIKIDATA_API)))
                .andRespond(withSuccess("""
                        {"search":[{"id":"Q8"},{"id":"Q9"}]}
                        """, MediaType.APPLICATION_JSON));
        // Q8 is the novel named after the author, not a human.
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q8.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q8":{"claims":{"P31":[{"mainsnak":{"datavalue":{"value":{"id":"Q571"}}}}]},
                          "labels":{"en":{"value":"Kate Bush"}}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q9.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q9":{"claims":{"P31":[{"mainsnak":{"datavalue":{"value":{"id":"Q5"}}}}]},
                          "labels":{"en":{"value":"Kate Bush"}},
                          "sitelinks":{"enwiki":{"title":"Kate Bush"}}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EN_SUMMARY + "Kate%20Bush"))
                .andRespond(withSuccess("{\"extract\":\"English singer.\"}", MediaType.APPLICATION_JSON));

        WikipediaService.Content content = subject.fetchContentForPerson("Kate Bush", EN_NL);

        assertEquals("English singer.", content.bios().get("en"));
        server.verify();
    }

    @Test
    void rejectsAHumanWhoseLabelIsADifferentName() {
        server.expect(requestTo(startsWith(WIKIDATA_API)))
                .andRespond(withSuccess("{\"search\":[{\"id\":\"Q9\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q9.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q9":{"claims":{"P31":[{"mainsnak":{"datavalue":{"value":{"id":"Q5"}}}}]},
                          "labels":{"en":{"value":"Someone Else"}},
                          "sitelinks":{"enwiki":{"title":"Someone Else"}}}}}
                        """, MediaType.APPLICATION_JSON));

        assertTrue(subject.fetchContentForPerson("Kate Bush", EN_NL).bios().isEmpty());
        server.verify();
    }

    @Test
    void wikidataErrorReturnsEmpty() {
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q1.json")).andRespond(withServerError());

        WikipediaService.Content content = subject.fetchContent("Q1", EN_NL);

        assertTrue(content.bios().isEmpty());
        assertNull(content.thumbnail());
    }
}
