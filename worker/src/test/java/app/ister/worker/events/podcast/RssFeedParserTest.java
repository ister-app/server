package app.ister.worker.events.podcast;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RssFeedParserTest {

    private static final String FEED_URL = "https://example.org/feed.xml";

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Test Cast</title>
                <description>A show about testing.</description>
                <language>nl</language>
                <itunes:author>Ister FM</itunes:author>
                <itunes:image href="https://example.org/cover.jpg"/>
                <item>
                  <title>Episode two</title>
                  <guid>guid-2</guid>
                  <description>Second.</description>
                  <pubDate>Fri, 10 Jul 2026 06:00:00 GMT</pubDate>
                  <enclosure url="https://cdn.example.org/2.mp3" type="audio/mpeg" length="123"/>
                  <itunes:duration>1:02:03</itunes:duration>
                  <itunes:episode>2</itunes:episode>
                  <itunes:season>1</itunes:season>
                </item>
                <item>
                  <title>Episode one — no guid</title>
                  <pubDate>Wed, 01 Jul 2026 06:00:00 GMT</pubDate>
                  <enclosure url="https://cdn.example.org/1.mp3" type="audio/mpeg" length="123"/>
                  <itunes:duration>1800</itunes:duration>
                </item>
                <item>
                  <title>Not an episode (no enclosure)</title>
                  <guid>guid-news</guid>
                </item>
              </channel>
            </rss>
            """;

    private RssFeedParser parser;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        parser = new RssFeedParser();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(parser, "restClient", builder.build());
    }

    @Test
    void parsesChannelAndItems() throws Exception {
        RssFeedParser.Feed feed = parser.parseString(FEED);

        assertEquals("Test Cast", feed.channel().title());
        assertEquals("A show about testing.", feed.channel().description());
        assertEquals("nl", feed.channel().language());
        assertEquals("Ister FM", feed.channel().author());
        assertEquals("https://example.org/cover.jpg", feed.channel().imageUrl());

        assertEquals(2, feed.items().size(), "the item without an enclosure is skipped");
        RssFeedParser.Item episodeTwo = feed.items().getFirst();
        assertEquals("guid-2", episodeTwo.guid());
        assertEquals("Episode two", episodeTwo.title());
        assertEquals("https://cdn.example.org/2.mp3", episodeTwo.enclosureUrl());
        assertEquals("audio/mpeg", episodeTwo.enclosureType());
        assertEquals(Instant.parse("2026-07-10T06:00:00Z"), episodeTwo.publishedAt());
        assertEquals((1 * 3600 + 2 * 60 + 3) * 1000L, episodeTwo.durationInMilliseconds());
        assertEquals(2, episodeTwo.episodeNumber());
        assertEquals(1, episodeTwo.seasonNumber());
    }

    @Test
    void guidFallsBackToEnclosureUrl() throws Exception {
        RssFeedParser.Feed feed = parser.parseString(FEED);
        assertEquals("https://cdn.example.org/1.mp3", feed.items().get(1).guid());
        assertEquals(1800 * 1000L, feed.items().get(1).durationInMilliseconds());
        assertNull(feed.items().get(1).episodeNumber());
    }

    @Test
    void fetchReturnsParsedFeedWithCachingHeaders() {
        server.expect(requestTo(FEED_URL))
                .andExpect(method(GET))
                .andRespond(withSuccess(FEED, MediaType.APPLICATION_RSS_XML)
                        .header("ETag", "\"v1\"")
                        .header("Last-Modified", "Fri, 10 Jul 2026 06:00:00 GMT"));

        Optional<RssFeedParser.Feed> feed = parser.fetch(FEED_URL, null, null);

        assertTrue(feed.isPresent());
        assertEquals("\"v1\"", feed.get().etag());
        assertEquals("Fri, 10 Jul 2026 06:00:00 GMT", feed.get().lastModified());
        assertEquals(2, feed.get().items().size());
    }

    @Test
    void conditionalGetReturnsNotModified() {
        server.expect(requestTo(FEED_URL))
                .andExpect(header("If-None-Match", "\"v1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        Optional<RssFeedParser.Feed> feed = parser.fetch(FEED_URL, "\"v1\"", null);

        assertTrue(feed.isPresent());
        assertTrue(feed.get().notModified());
    }

    @Test
    void brokenXmlReturnsEmpty() {
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess("<rss><channel>", MediaType.APPLICATION_RSS_XML));

        assertTrue(parser.fetch(FEED_URL, null, null).isEmpty());
    }

    @Test
    void doctypeIsRejected() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><channel><title>&x;</title></channel></rss>";
        server.expect(requestTo(FEED_URL))
                .andRespond(withSuccess(evil, MediaType.APPLICATION_RSS_XML));

        assertTrue(parser.fetch(FEED_URL, null, null).isEmpty());
    }

    @Test
    void parsesDateAndDurationEdgeCases() {
        assertNull(RssFeedParser.parsePubDate("not a date"));
        assertEquals(Instant.parse("2026-07-10T06:00:00Z"), RssFeedParser.parsePubDate("2026-07-10T06:00:00Z"));
        assertEquals(0, RssFeedParser.parseItunesDuration("abc"));
        assertEquals(90_000, RssFeedParser.parseItunesDuration("1:30"));
        assertEquals(0, RssFeedParser.parseItunesDuration(null));
    }
}
