package app.ister.disk.events.podcastepisodedownloadrequested;

import app.ister.core.EventHandlingException;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.eventdata.PodcastEpisodeDownloadRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandlePodcastEpisodeDownloadRequestedTest {

    private static final byte[] AUDIO = "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8);

    private static HttpServer httpServer;
    private static String baseUrl;

    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private DirectoryRepository directoryRepository;
    @Mock
    private NodeService nodeService;
    @Mock
    private MessageSender messageSender;

    @TempDir
    Path cachePath;

    private HandlePodcastEpisodeDownloadRequested subject;
    private PodcastEpisodeEntity episode;
    private DirectoryEntity cacheDir;

    @BeforeAll
    static void startServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Direct hit, a tracking-style redirect, and an HTML error page.
        httpServer.createContext("/episode.mp3", exchange -> respond(exchange, 200, "audio/mpeg", AUDIO));
        httpServer.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl + "/episode.mp3");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        httpServer.createContext("/error.mp3", exchange ->
                respond(exchange, 200, "text/html", "<html>blocked</html>".getBytes(StandardCharsets.UTF_8)));
        httpServer.start();
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType,
                                byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @AfterAll
    static void stopServer() {
        httpServer.stop(0);
    }

    @BeforeEach
    void setUp() {
        subject = new HandlePodcastEpisodeDownloadRequested(podcastEpisodeRepository, mediaFileRepository,
                directoryRepository, nodeService, messageSender);

        PodcastEntity podcast = PodcastEntity.builder().feedUrl("f").title("t").active(true).build();
        episode = PodcastEpisodeEntity.builder()
                .podcastEntity(podcast)
                .guid("guid-1")
                .enclosureUrl(baseUrl + "/episode.mp3")
                .enclosureType("audio/mpeg")
                .build();
        episode.setId(UUID.randomUUID());
        lenient().when(podcastEpisodeRepository.findById(episode.getId())).thenReturn(Optional.of(episode));

        NodeEntity node = NodeEntity.builder().name("node1").build();
        lenient().when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        cacheDir = DirectoryEntity.builder()
                .nodeEntity(node)
                .name("node1-cache-directory")
                .path(cachePath.toString())
                .directoryType(DirectoryType.CACHE)
                .build();
        cacheDir.setId(UUID.randomUUID());
        lenient().when(directoryRepository.findByDirectoryTypeAndNodeEntity(eq(DirectoryType.CACHE), any()))
                .thenReturn(List.of(cacheDir));
        lenient().when(mediaFileRepository.findByDirectoryEntityAndPath(any(), any())).thenReturn(Optional.empty());

        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private PodcastEpisodeDownloadRequestedData event() {
        return PodcastEpisodeDownloadRequestedData.builder()
                .eventType(EventType.PODCAST_EPISODE_DOWNLOAD_REQUESTED)
                .podcastEpisodeId(episode.getId())
                .build();
    }

    @Test
    void downloadsEnclosureAndRegistersMediaFile() throws IOException {
        subject.handle(event());

        Path expected = cachePath.resolve("podcasts").resolve(episode.getId() + ".mp3");
        assertTrue(Files.exists(expected));
        assertArrayEquals(AUDIO, Files.readAllBytes(expected));

        ArgumentCaptor<MediaFileEntity> saved = ArgumentCaptor.forClass(MediaFileEntity.class);
        verify(mediaFileRepository).save(saved.capture());
        assertEquals(expected.toString(), saved.getValue().getPath());
        assertEquals(episode.getId(), saved.getValue().getPodcastEpisodeEntity().getId());

        // AUDIO_FILE_FOUND goes out after commit, on the cache directory's queue.
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        ArgumentCaptor<AudioFileFoundData> data = ArgumentCaptor.forClass(AudioFileFoundData.class);
        verify(messageSender).sendAudioFileFound(data.capture(), eq("node1-cache-directory"));
        assertEquals(expected.toString(), data.getValue().getPath());
        assertEquals(cacheDir.getId(), data.getValue().getDirectoryEntityUUID());
    }

    @Test
    void followsRedirects() {
        episode.setEnclosureUrl(baseUrl + "/redirect");

        subject.handle(event());

        assertTrue(Files.exists(cachePath.resolve("podcasts").resolve(episode.getId() + ".mp3")));
    }

    @Test
    void htmlResponseIsRejected() {
        episode.setEnclosureUrl(baseUrl + "/error.mp3");

        assertThrows(EventHandlingException.class, () -> subject.handle(event()));
        verify(mediaFileRepository, never()).save(any());
    }

    @Test
    void alreadyDownloadedEpisodeIsSkipped() {
        when(mediaFileRepository.existsByPodcastEpisodeEntityId(episode.getId())).thenReturn(true);

        subject.handle(event());

        verify(mediaFileRepository, never()).save(any());
        verify(messageSender, never()).sendAudioFileFound(any(), any());
    }
}
