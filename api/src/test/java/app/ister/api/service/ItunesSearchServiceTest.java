package app.ister.api.service;

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
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ItunesSearchServiceTest {

    private static final String SEARCH_ENDPOINT = "https://itunes.apple.com/search";

    private ItunesSearchService subject;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        subject = new ItunesSearchService("https://itunes.apple.com");
        // The service builds its own RestClient in the constructor; rebind it to a mock server so
        // no real network calls are made.
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(subject, "restClient", builder.build());
    }

    @Test
    void mapsDirectoryResults() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"resultCount":1,"results":[{
                          "collectionName":"Serial",
                          "artistName":"Serial Productions",
                          "feedUrl":"https://feeds.simplecast.com/serial",
                          "artworkUrl600":"https://example.org/art.jpg"}]}
                        """, MediaType.APPLICATION_JSON));

        List<ItunesSearchService.DirectoryResult> result = subject.search("serial", 20);

        assertEquals(1, result.size());
        assertEquals("Serial", result.getFirst().name());
        assertEquals("Serial Productions", result.getFirst().author());
        assertEquals("https://feeds.simplecast.com/serial", result.getFirst().feedUrl());
        assertEquals("https://example.org/art.jpg", result.getFirst().artworkUrl());
        server.verify();
    }

    /** Subscribing needs a feed URL, so results without one (e.g. episodes) are dropped. */
    @Test
    void skipsResultsWithoutAFeedUrl() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"results":[{"collectionName":"No feed"},{"feedUrl":"https://example.org/feed"}]}
                        """, MediaType.APPLICATION_JSON));

        List<ItunesSearchService.DirectoryResult> result = subject.search("serial", 20);

        assertEquals(1, result.size());
        assertEquals("https://example.org/feed", result.getFirst().feedUrl());
        assertNull(result.getFirst().name());
        assertNull(result.getFirst().author());
        assertNull(result.getFirst().artworkUrl());
    }

    @Test
    void returnsEmptyListWhenTheResponseHasNoResults() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withSuccess("{\"resultCount\":0}", MediaType.APPLICATION_JSON));

        assertTrue(subject.search("serial", 20).isEmpty());
    }

    /** A directory outage must not fail the GraphQL query; it just returns nothing. */
    @Test
    void returnsEmptyListWhenTheDirectoryFails() {
        server.expect(requestTo(startsWith(SEARCH_ENDPOINT)))
                .andRespond(withServerError());

        assertTrue(subject.search("serial", 20).isEmpty());
    }
}
