package app.ister.worker.events.openlibrary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenLibraryServiceTest {

    private static final String SEARCH_ENDPOINT = "https://openlibrary.org/search.json";
    private static final String WORK_ENDPOINT = "https://openlibrary.org/works/OL1W.json";

    private OpenLibraryService subject;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        subject = new OpenLibraryService("https://openlibrary.org");
        // The service builds its own RestClient in the constructor; rebind it to a mock server
        // so no real network calls are made.
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(subject, "restClient", builder.build());
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

        Optional<OpenLibraryService.BookInfo> result = subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa");

        assertTrue(result.isPresent());
        assertEquals("Een roman over een steppestadje.", result.get().description());
        assertEquals("https://covers.openlibrary.org/b/id/123-L.jpg", result.get().coverUrl());
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

        Optional<OpenLibraryService.BookInfo> result = subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa");

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

        assertTrue(subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa").isEmpty());
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

        assertTrue(subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa").isPresent());
    }

    @Test
    void emptyResultsReturnEmpty() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withSuccess("{\"docs\":[]}", MediaType.APPLICATION_JSON));

        assertTrue(subject.getBookInfo("Unknown", "Nobody").isEmpty());
    }

    @Test
    void serverErrorReturnsEmpty() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withServerError());

        assertTrue(subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa").isEmpty());
    }

    @Test
    void workWithoutUsefulDataReturnsEmpty() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"docs":[{"key":"/works/OL1W","title":"Dit zijn de namen"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WORK_ENDPOINT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertTrue(subject.getBookInfo("Dit zijn de namen", "Tommy Wieringa").isEmpty());
    }
}
