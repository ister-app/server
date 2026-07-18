package app.ister.worker.events.openlibrary;

import app.ister.core.enums.MetadataSource;
import app.ister.worker.events.wikipedia.WikipediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenLibraryServiceTest {

    private static final String SEARCH_ENDPOINT = "https://openlibrary.org/search.json";
    private static final String WORK_ENDPOINT = "https://openlibrary.org/works/OL1W.json";
    private static final String AUTHOR_SEARCH_ENDPOINT = "https://openlibrary.org/search/authors.json";
    private static final String AUTHOR_ENDPOINT = "https://openlibrary.org/authors/OL1A.json";
    private static final String WIKIDATA_ENDPOINT = "https://www.wikidata.org/wiki/Special:EntityData/";
    private static final String WIKIDATA_API = "https://www.wikidata.org/w/api.php";
    private static final String WIKIPEDIA_NL_SUMMARY_ENDPOINT = "https://nl.wikipedia.org/api/rest_v1/page/summary/";
    private static final List<String> NL = List.of("nl");
    private static final List<String> EN_NL = List.of("en", "nl");

    private OpenLibraryService subject;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        WikipediaService wikipediaService = new WikipediaService(WIKIDATA_ENDPOINT, WIKIDATA_API, "https://{lang}.wikipedia.org/api/rest_v1/page/summary/{title}");
        subject = new OpenLibraryService("https://openlibrary.org", "https://covers.openlibrary.org/b/id/", "https://covers.openlibrary.org/a/olid/", wikipediaService);
        // Both services build their own RestClient in the constructor; rebind them to one mock
        // server so no real network calls are made.
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(subject, "restClient", builder.build());
        ReflectionTestUtils.setField(wikipediaService, "restClient", builder.build());
    }

    @Test
    void returnsCoverDescriptionAndYearForMatchingWork() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"/works/OL1W","title":"Dit zijn de namen","cover_i":123,"first_publish_year":2012}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WORK_ENDPOINT))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"description":{"type":"/type/text","value":"Een roman over een steppestadje."}}
                        """, MediaType.APPLICATION_JSON));

        Optional<OpenLibraryService.BookInfo> result = subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa", List.of());

        assertTrue(result.isPresent());
        assertEquals("Een roman over een steppestadje.", result.get().description());
        assertEquals("https://covers.openlibrary.org/b/id/123-L.jpg", result.get().coverUrl());
        assertEquals(2012, result.get().firstPublishYear());
        server.verify();
    }

    /**
     * An ISBN hit is authoritative: the Dutch edition's ISBN rolls up to the original work, whose
     * English title would never fuzzy-match the local Dutch one — so no title check happens.
     */
    @Test
    void isbnSearchWinsWithoutTitleMatching() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andExpect(method(GET))
                .andExpect(queryParam("q", "isbn:9789025747855"))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"/works/OL1W","title":"The Ruins of Gorlan","cover_i":5,"first_publish_year":2004}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WORK_ENDPOINT))
                .andRespond(withSuccess("{\"description\":\"The original work.\"}", MediaType.APPLICATION_JSON));

        Optional<OpenLibraryService.BookInfo> result = subject.getBookInfo(
                "De Grijze Jager - De ruïnes van Gorlan", "John Flanagan", List.of("9789025747855"));

        assertTrue(result.isPresent());
        assertEquals(2004, result.get().firstPublishYear());
        assertEquals("/works/OL1W", result.get().workKey());
        server.verify();
    }

    @Test
    void unknownIsbnFallsBackToTitleAndAuthorSearch() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andExpect(queryParam("q", "isbn:9789999999999"))
                .andRespond(withSuccess("{\"docs\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andExpect(queryParam("title", "Dit%20zijn%20de%20namen"))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"/works/OL1W","title":"Dit zijn de namen","first_publish_year":2012}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WORK_ENDPOINT))
                .andRespond(withSuccess("{\"description\":\"x\"}", MediaType.APPLICATION_JSON));

        Optional<OpenLibraryService.BookInfo> result = subject.getBookInfo(
                "Dit zijn de namen", "Tommy Wieringa", List.of("9789999999999"));

        assertTrue(result.isPresent());
        assertEquals(2012, result.get().firstPublishYear());
        server.verify();
    }

    @Test
    void plainStringDescriptionIsSupported() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"/works/OL1W","title":"Dit zijn de namen"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WORK_ENDPOINT))
                .andRespond(withSuccess("{\"description\":\"Plain description.\"}", MediaType.APPLICATION_JSON));

        Optional<OpenLibraryService.BookInfo> result = subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa", List.of());

        assertTrue(result.isPresent());
        assertEquals("Plain description.", result.get().description());
        assertNull(result.get().coverUrl());
    }

    @Test
    void nonMatchingTitleIsRejected() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"/works/OL9W","title":"A completely different book"}]}
                        """, MediaType.APPLICATION_JSON));

        assertTrue(subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa", List.of()).isEmpty());
        server.verify();
    }

    @Test
    void titleMatchIgnoresCaseAndPunctuation() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"/works/OL1W","title":"DIT ZIJN DE NAMEN!"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WORK_ENDPOINT))
                .andRespond(withSuccess("{\"description\":\"x\"}", MediaType.APPLICATION_JSON));

        assertTrue(subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa", List.of()).isPresent());
    }

    @Test
    void emptyResultsReturnEmpty() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withSuccess("{\"docs\":[]}", MediaType.APPLICATION_JSON));

        assertTrue(subject.getBookInfo("Unknown", "Nobody", List.of()).isEmpty());
    }

    @Test
    void serverErrorReturnsEmpty() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withServerError());

        assertTrue(subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa", List.of()).isEmpty());
    }

    @Test
    void workWithoutUsefulDataReturnsEmpty() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"/works/OL1W","title":"Dit zijn de namen"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WORK_ENDPOINT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertTrue(subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa", List.of()).isEmpty());
    }

    // ========== getAuthorInfo ==========

    @Test
    void returnsBioPhotoAndBirthYearForMatchingAuthor() {
        expectAuthorSearchAndFetch("""
                {"bio":"Nederlands schrijver.","birth_date":"5 September 1967","photos":[42]}
                """);
        expectNoWikidataMatch();

        Optional<OpenLibraryService.AuthorInfo> result = subject.getAuthorInfo("Tommy Wieringa", EN_NL);

        assertTrue(result.isPresent());
        // Open Library prose is English, so it fills the English slot -- never the Dutch one, which
        // would show English text to a reader who asked for a Dutch biography.
        assertEquals("Nederlands schrijver.", result.get().bios().get("en"));
        assertEquals(MetadataSource.OPEN_LIBRARY, result.get().bioSources().get("en"));
        assertNull(result.get().bios().get("nl"));
        assertEquals("https://covers.openlibrary.org/a/olid/OL1A-L.jpg", result.get().photoUrl());
        assertEquals(1967, result.get().birthYear());
        assertEquals("OL1A", result.get().sourceKey());
        server.verify();
    }

    @Test
    void wikipediaBioAndThumbnailWinWhenTheAuthorLinksToWikidata() {
        expectAuthorSearchAndFetch("""
                {"bio":"English bio.","birth_date":"1967","remote_ids":{"wikidata":"Q1"}}
                """);
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q1.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q1":{"sitelinks":{"nlwiki":{"title":"Tommy Wieringa"}}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIPEDIA_NL_SUMMARY_ENDPOINT + "Tommy%20Wieringa"))
                .andRespond(withSuccess("""
                        {"extract":"Nederlandse schrijver.","thumbnail":{"source":"https://wiki/photo.jpg"}}
                        """, MediaType.APPLICATION_JSON));

        Optional<OpenLibraryService.AuthorInfo> result = subject.getAuthorInfo("Tommy Wieringa", NL);

        assertTrue(result.isPresent());
        assertEquals("Nederlandse schrijver.", result.get().bios().get("nl"));
        assertEquals(MetadataSource.WIKIPEDIA, result.get().bioSources().get("nl"));
        // No "photos" in the Open Library record, so the Wikipedia thumbnail is the portrait.
        assertEquals("https://wiki/photo.jpg", result.get().photoUrl());
        server.verify();
    }

    @Test
    void authorWithoutPhotosHasNoPhotoUrl() {
        expectAuthorSearchAndFetch("{\"bio\":\"Bio.\",\"photos\":[-1]}");
        expectNoWikidataMatch();

        Optional<OpenLibraryService.AuthorInfo> result = subject.getAuthorInfo("Tommy Wieringa", EN_NL);

        assertTrue(result.isPresent());
        assertNull(result.get().photoUrl());
        assertNull(result.get().birthYear());
    }

    @Test
    void authorMatchesOnAnAlternateName() {
        server.expect(requestTo(startsWith(AUTHOR_SEARCH_ENDPOINT)))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"OL1A","name":"J. R. R. Tolkien","alternate_names":["J.R.R. Tolkien"]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(AUTHOR_ENDPOINT))
                .andRespond(withSuccess("{\"bio\":\"Bio.\"}", MediaType.APPLICATION_JSON));
        expectNoWikidataMatch();

        assertTrue(subject.getAuthorInfo("J.R.R. Tolkien", EN_NL).isPresent());
        server.verify();
    }

    @Test
    void nonMatchingAuthorNameIsRejected() {
        server.expect(requestTo(startsWith(AUTHOR_SEARCH_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"OL9A","name":"Someone Else"}]}
                        """, MediaType.APPLICATION_JSON));

        assertTrue(subject.getAuthorInfo("Tommy Wieringa", NL).isEmpty());
        server.verify();
    }

    @Test
    void authorWithoutUsefulDataReturnsEmpty() {
        expectAuthorSearchAndFetch("{}");
        expectNoWikidataMatch();

        assertTrue(subject.getAuthorInfo("Tommy Wieringa", NL).isEmpty());
    }

    @Test
    void wikipediaFillsTheBioWhenOpenLibraryHasNoWikidataLink() {
        // Most Open Library author records are just a name and a birth date, so the author is looked
        // up on Wikidata by name instead.
        expectAuthorSearchAndFetch("{\"birth_date\":\"1967\"}");
        server.expect(requestTo(startsWith(WIKIDATA_API)))
                .andRespond(withSuccess("{\"search\":[{\"id\":\"Q7\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIDATA_ENDPOINT + "Q7.json"))
                .andRespond(withSuccess("""
                        {"entities":{"Q7":{
                          "claims":{"P31":[{"mainsnak":{"datavalue":{"value":{"id":"Q5"}}}}]},
                          "labels":{"nl":{"value":"Tommy Wieringa"}},
                          "sitelinks":{"nlwiki":{"title":"Tommy Wieringa"}}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIPEDIA_NL_SUMMARY_ENDPOINT + "Tommy%20Wieringa"))
                .andRespond(withSuccess("""
                        {"extract":"Nederlandse schrijver.","thumbnail":{"source":"https://wiki/tw.jpg"}}
                        """, MediaType.APPLICATION_JSON));

        Optional<OpenLibraryService.AuthorInfo> result = subject.getAuthorInfo("Tommy Wieringa", NL);

        assertTrue(result.isPresent());
        assertEquals("Nederlandse schrijver.", result.get().bios().get("nl"));
        assertEquals("https://wiki/tw.jpg", result.get().photoUrl());
        assertEquals(1967, result.get().birthYear());
        server.verify();
    }

    @Test
    void authorSearchServerErrorReturnsEmpty() {
        server.expect(requestTo(startsWith(AUTHOR_SEARCH_ENDPOINT)))
                .andRespond(withServerError());

        assertTrue(subject.getAuthorInfo("Tommy Wieringa", NL).isEmpty());
    }

    /** The author has no wikidata link, so Wikidata is searched by name — with no hit here. */
    @Test
    void openLibraryBioIsDroppedWhenEnglishIsNotAConfiguredLanguage() {
        expectAuthorSearchAndFetch("""
                {"bio":"English prose from Open Library.","birth_date":"1967"}
                """);
        expectNoWikidataMatch();

        Optional<OpenLibraryService.AuthorInfo> result = subject.getAuthorInfo("Tommy Wieringa", NL);

        // Better no biography than English text served as the Dutch one.
        assertTrue(result.isPresent());
        assertTrue(result.get().bios().isEmpty());
        assertEquals(1967, result.get().birthYear());
    }

    @Test
    void authorWithANonLatinNameOnlyMatchesThatName() {
        server.expect(requestTo(startsWith(AUTHOR_SEARCH_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"OL9A","name":"Лев Толстой"}]}
                        """, MediaType.APPLICATION_JSON));

        // Cyrillic normalises to the empty string under an [a-z0-9] filter, and two empty strings
        // compare equal -- which would hand this author's record to a completely different person.
        assertTrue(subject.getAuthorInfo("Фёдор Достоевский", NL).isEmpty());
        server.verify();
    }

    @Test
    void authorWithANonLatinNameStillMatchesItself() {
        server.expect(requestTo(startsWith(AUTHOR_SEARCH_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"OL1A","name":"Лев Толстой"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(AUTHOR_ENDPOINT))
                .andRespond(withSuccess("{\"birth_date\":\"1828\"}", MediaType.APPLICATION_JSON));
        expectNoWikidataMatch();

        Optional<OpenLibraryService.AuthorInfo> result = subject.getAuthorInfo("Лев Толстой", NL);

        assertTrue(result.isPresent());
        assertEquals(1828, result.get().birthYear());
    }

    private void expectNoWikidataMatch() {
        server.expect(requestTo(startsWith(WIKIDATA_API)))
                .andRespond(withSuccess("{\"search\":[]}", MediaType.APPLICATION_JSON));
    }

    private void expectAuthorSearchAndFetch(String authorJson) {
        server.expect(requestTo(startsWith(AUTHOR_SEARCH_ENDPOINT)))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"OL1A","name":"Tommy Wieringa"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(AUTHOR_ENDPOINT))
                .andExpect(method(GET))
                .andRespond(withSuccess(authorJson, MediaType.APPLICATION_JSON));
    }
}
