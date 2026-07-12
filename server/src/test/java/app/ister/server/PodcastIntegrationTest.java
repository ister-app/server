package app.ister.server;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.PodcastRepository;
import com.sun.net.httpserver.HttpServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end podcast flow against real PostgreSQL and RabbitMQ, with the RSS feed and its audio
 * served from a local HTTP server: subscribePodcast (GraphQL over HTTP) → PODCAST_REFRESH_REQUESTED
 * through the broker → feed parse → podcast/episode/metadata rows → auto-download of the newest
 * episodes onto the cache directory → MediaFileEntity + file on disk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-podcast-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-podcast-it/cache/",
        "app.ister.disk.libraries[0].name=it-podcasts",
        "app.ister.disk.libraries[0].type=PODCAST",
        // A PODCAST library needs no library directory, but the config binder requires at least
        // one directory entry, so give the node a dummy SHOW library alongside it.
        "app.ister.disk.libraries[1].name=it-shows",
        "app.ister.disk.libraries[1].type=SHOW",
        "app.ister.disk.directories[0].name=it-disk",
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-podcast-it/media",
        "app.ister.disk.directories[0].library=it-shows",
        "app.ister.worker.podcast.auto-download-count=2",
})
@Testcontainers(disabledWithoutDocker = true)
class PodcastIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    private static final byte[] AUDIO = "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8);

    private static HttpServer feedServer;
    private static String feedBaseUrl;

    @Autowired
    private PodcastRepository podcastRepository;
    @Autowired
    private PodcastEpisodeRepository podcastEpisodeRepository;
    @Autowired
    private MetadataRepository metadataRepository;
    @Autowired
    private MediaFileRepository mediaFileRepository;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void startFeedServer() throws IOException {
        feedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        feedServer.createContext("/feed.xml", exchange -> {
            byte[] body = feedXml().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        feedServer.createContext("/audio/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, AUDIO.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(AUDIO);
            }
        });
        feedServer.start();
        feedBaseUrl = "http://127.0.0.1:" + feedServer.getAddress().getPort();
    }

    private static String feedXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                  <channel>
                    <title>Integration Cast</title>
                    <description>Testing end to end.</description>
                    <language>en</language>
                    <itunes:author>Ister IT</itunes:author>
                    <item>
                      <title>Newest episode</title>
                      <guid>it-guid-2</guid>
                      <pubDate>Fri, 10 Jul 2026 06:00:00 GMT</pubDate>
                      <enclosure url="%s/audio/2.mp3" type="audio/mpeg" length="14"/>
                      <itunes:duration>0:30</itunes:duration>
                    </item>
                    <item>
                      <title>Older episode</title>
                      <guid>it-guid-1</guid>
                      <pubDate>Wed, 01 Jul 2026 06:00:00 GMT</pubDate>
                      <enclosure url="%s/audio/1.mp3" type="audio/mpeg" length="14"/>
                    </item>
                  </channel>
                </rss>
                """.formatted(feedBaseUrl, feedBaseUrl);
    }

    @AfterAll
    static void stopFeedServer() {
        feedServer.stop(0);
    }

    @Test
    void subscribeRefreshesFeedAndDownloadsNewestEpisodes() {
        String feedUrl = feedBaseUrl + "/feed.xml";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        String mutation = """
                {"query": "mutation { subscribePodcast(feedUrl: \\"%s\\") { id title active } }"}
                """.formatted(feedUrl);
        ResponseEntity<Map> response = new RestTemplate().exchange(
                "http://localhost:%d/graphql".formatted(port),
                org.springframework.http.HttpMethod.POST, new HttpEntity<>(mutation, headers), Map.class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().containsKey("errors"),
                "subscribe mutation should not error: " + response.getBody());

        // The refresh runs through RabbitMQ: feed fetched, channel synced, episodes created.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            PodcastEntity podcast = podcastRepository.findByFeedUrl(feedUrl).orElseThrow();
            assertEquals("Integration Cast", podcast.getTitle());
            assertEquals("Ister IT", podcast.getAuthor());
            assertEquals("eng", podcast.getLanguage());
            assertNotNull(podcast.getLastRefreshedAt());
        });
        PodcastEntity podcast = podcastRepository.findByFeedUrl(feedUrl).orElseThrow();
        assertFalse(metadataRepository.findByPodcastEntityId(podcast.getId()).isEmpty());

        List<java.util.UUID> episodeIds =
                podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(podcast.getId(), 10, 0);
        assertEquals(2, episodeIds.size());
        PodcastEpisodeEntity newest = podcastEpisodeRepository.findById(episodeIds.getFirst()).orElseThrow();
        assertEquals("it-guid-2", newest.getGuid());
        assertEquals(30_000, newest.getDurationHintInMilliseconds());
        assertEquals("Newest episode",
                metadataRepository.findByPodcastEpisodeEntityId(newest.getId()).getFirst().getTitle());

        // Auto-download (count=2) pulls both episodes onto this node's cache directory.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (java.util.UUID episodeId : episodeIds) {
                List<MediaFileEntity> files = mediaFileRepository.findByPodcastEpisodeEntityId(episodeId);
                assertEquals(1, files.size(), "episode should be downloaded");
                assertTrue(Files.exists(Path.of(files.getFirst().getPath())));
            }
        });
    }
}
