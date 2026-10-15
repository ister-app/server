package app.ister.server;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An HLS request that has to wait for the transcoder (master playlist not yet produced, segment
 * not yet encoded) parks its HTTP thread in a poll loop for up to the configured timeout. That
 * wait must not keep a database connection checked out: with open-in-view and Hibernate's
 * hold-until-session-close connection handling it did, and a handful of players starting at
 * once emptied the 20-connection pool for everything else. This test drives the wait against
 * real PostgreSQL + RabbitMQ and samples the Hikari pool while the request is pending.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-hls-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-hls-it/cache/",
        "app.ister.disk.libraries[0].name=it-hls-lib",
        "app.ister.disk.libraries[0].type=MOVIE",
        "app.ister.disk.directories[0].name=it-hls-disk",
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-hls-it/media/",
        "app.ister.disk.directories[0].library=it-hls-lib",
        // two waits of 3 s each; the sampling window below fits inside the first
        "app.ister.server.hls.master-playlist-timeout-ms=6000",
})
@Testcontainers(disabledWithoutDocker = true)
class HlsWaitConnectionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    // JwtDecoder stub: GraphQlSubscriptionIntegrationTest.FakeJwtConfig (same package), any bearer token is an admin.

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aRequestWaitingForTheMasterPlaylistHoldsNoDatabaseConnection() throws Exception {
        UUID mediaFileId = analyzedMediaFileWhoseSourceIsMissing();
        HikariDataSource hikari = (HikariDataSource) dataSource;

        // The source file does not exist, so the transcoder never writes a master playlist and
        // the request waits for the full timeout.
        CompletableFuture<HttpResponse<String>> pending = http.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hls/" + mediaFileId + "/master.m3u8"))
                        .header("Authorization", "Bearer test-token").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        List<Integer> samples = new ArrayList<>();
        Thread.sleep(500); // past the short read-only transaction that precedes the wait
        for (int i = 0; i < 20; i++) {
            samples.add(hikari.getHikariPoolMXBean().getActiveConnections());
            Thread.sleep(100);
        }
        assertFalse(pending.isDone(), "the request should still be waiting while the pool was sampled");

        // The transcode handler runs a short transaction of its own, so a sample may see 1 in
        // passing; a connection held for the whole wait shows as every sample being >= 1.
        assertTrue(samples.contains(0),
                "the waiting HLS request kept a database connection checked out; samples: " + samples);
        pending.get();
    }

    private UUID analyzedMediaFileWhoseSourceIsMissing() {
        return new TransactionTemplate(transactionManager).execute(_ -> {
            DirectoryEntity directory = directoryRepository.findByName("it-hls-disk").orElseThrow();
            String path = directory.getPath() + "missing-" + UUID.randomUUID() + ".mkv";
            MediaFileEntity mediaFile = MediaFileEntity.builder()
                    .path(path).size(1).durationInMilliseconds(60_000).build();
            mediaFile.setDirectoryEntity(directory); // the builder leaves directoryEntityId unset
            mediaFile = mediaFileRepository.save(mediaFile);
            mediaFileStreamRepository.save(MediaFileStreamEntity.builder()
                    .mediaFileEntity(mediaFile).streamIndex(0).codecName("h264").codecType(StreamCodecType.VIDEO)
                    .width(1280).height(720).path(path).build());
            return mediaFile.getId();
        });
    }
}
