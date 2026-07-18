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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        subject = new WikipediaService(WIKIDATA_ENDPOINT, WIKIDATA_API, "https://{lang}.wikipedia.org/api/rest_v1/page/summary/{title}");
        // The service builds its own RestClient in the constructor; rebind it to a mock server
        // so no real network calls are made.
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(subject, "restClient", builder.build());
    }

    // ===== fetchContentForSeries =====

    @Test
    void findsAComicSeriesByNameAndTypeCheck() {
        server.expect(requestTo(startsWith(WIKIDATA_API)))
                .andRespond(withSuccess("{\"search\":[{\"id\":\"Q11\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q11.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q11":{
                          "claims":{"P31":[{"mainsnak":{"datavalue":{"value":{"id":"Q21198342"}}}}]},
                          "labels":{"en":{"value":"Attack on Titan"}},
                          "sitelinks":{"enwiki":{"title":"Attack on Titan"}}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EN_SUMMARY + "Attack%20on%20Titan"))
                .andRespond(withSuccess("""
                        {"extract":"A manga series.","thumbnail":{"source":"https://wiki/aot.jpg"}}
                        """, MediaType.APPLICATION_JSON));

        WikipediaService.SeriesContent seriesContent =
                subject.fetchContentForSeries("Attack on Titan", List.of("en"));

        assertEquals("A manga series.", seriesContent.content().bios().get("en"));
        assertEquals("https://wiki/aot.jpg", seriesContent.content().thumbnail());
        assertTrue(seriesContent.manga());
        server.verify();
    }

    /** A western comic series (Q14406742) is a valid match but not manga. */
    @Test
    void aComicBookSeriesIsNotFlaggedAsManga() {
        server.expect(requestTo(startsWith(WIKIDATA_API)))
                .andRespond(withSuccess("{\"search\":[{\"id\":\"Q13\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q13.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q13":{
                          "claims":{"P31":[{"mainsnak":{"datavalue":{"value":{"id":"Q14406742"}}}}]},
                          "labels":{"en":{"value":"Lucky Luke"}},
                          "sitelinks":{"enwiki":{"title":"Lucky Luke"}}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EN_SUMMARY + "Lucky%20Luke"))
                .andRespond(withSuccess("""
                        {"extract":"A comic series."}
                        """, MediaType.APPLICATION_JSON));

        WikipediaService.SeriesContent seriesContent =
                subject.fetchContentForSeries("Lucky Luke", List.of("en"));

        assertEquals("A comic series.", seriesContent.content().bios().get("en"));
        assertFalse(seriesContent.manga());
        server.verify();
    }

    /** A series-name search also finds the TV show or a same-named human; only series types pass. */
    @Test
    void rejectsANonSeriesEntityWithTheSameName() {
        server.expect(requestTo(startsWith(WIKIDATA_API)))
                .andRespond(withSuccess("{\"search\":[{\"id\":\"Q12\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q12.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q12":{
                          "claims":{"P31":[{"mainsnak":{"datavalue":{"value":{"id":"Q5"}}}}]},
                          "labels":{"en":{"value":"Attack on Titan"}},
                          "sitelinks":{"enwiki":{"title":"Attack on Titan"}}}}}
                        """, MediaType.APPLICATION_JSON));

        WikipediaService.SeriesContent seriesContent =
                subject.fetchContentForSeries("Attack on Titan", List.of("en"));

        assertTrue(seriesContent.content().bios().isEmpty());
        assertNull(seriesContent.content().thumbnail());
        assertFalse(seriesContent.manga());
        server.verify();
    }

    @Test
    void seriesSearchWithoutResultsIsEmpty() {
        server.expect(requestTo(startsWith(WIKIDATA_API)))
                .andRespond(withSuccess("{\"search\":[]}", MediaType.APPLICATION_JSON));

        assertTrue(subject.fetchContentForSeries("Unknown Series", List.of("en")).content().bios().isEmpty());
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
    void followsAWikidataRedirectToTheMergedEntity() {
        // Wikidata merges duplicate items: asking for the old id answers with a document keyed by
        // the id it was merged into. Reading only under the requested key loses every merged person.
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q1.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q42":{"sitelinks":{"enwiki":{"title":"Kate Bush"}}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EN_SUMMARY + "Kate%20Bush"))
                .andRespond(withSuccess("{\"extract\":\"English singer.\"}", MediaType.APPLICATION_JSON));

        WikipediaService.Content content = subject.fetchContent("Q1", List.of("en"));

        assertEquals("English singer.", content.bios().get("en"));
        server.verify();
    }

    @Test
    void nonLatinNameDoesNotMatchAnUnrelatedPerson() {
        server.expect(requestTo(startsWith(WIKIDATA_API)))
                .andRespond(withSuccess("{\"search\":[{\"id\":\"Q9\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q9.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q9":{"claims":{"P31":[{"mainsnak":{"datavalue":{"value":{"id":"Q5"}}}}]},
                          "labels":{"en":{"value":"Лев Толстой"}},
                          "sitelinks":{"enwiki":{"title":"Leo Tolstoy"}}}}}
                        """, MediaType.APPLICATION_JSON));

        // Both names are Cyrillic: an [a-z0-9] normaliser reduces both to "" and they compare equal,
        // so Dostoevsky would be handed Tolstoy's biography and portrait.
        assertTrue(subject.fetchContentForPerson("Фёдор Достоевский", EN_NL).bios().isEmpty());
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
