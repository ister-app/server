package app.ister.server;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.StorageKind;
import app.ister.core.eventdata.NewDirectoriesScanRequestedData;
import app.ister.core.node.NodeTokenManager;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.service.MessageSender;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end S3 flow against real PostgreSQL, RabbitMQ and MinIO: a SHOW library whose directory
 * is a bucket prefix. Startup registers the directory as an ownerless S3 directory attached to
 * this node, the scan lists the prefix, the media-file analysis reads the object through ffprobe,
 * and the node proxies the object with byte ranges for other nodes (and its own ffmpeg).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-s3-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-s3-it/cache/",
        "app.ister.disk.libraries[0].name=it-shows",
        "app.ister.disk.libraries[0].type=SHOW",
        "app.ister.disk.directories[0].name=it-shows-s3",
        "app.ister.disk.directories[0].s3-connection=minio",
        "app.ister.disk.directories[0].prefix=/media/shows/",
        "app.ister.disk.directories[0].library=it-shows",
        // ffprobe reads a presigned URL here: the loopback proxy needs a fixed server.port,
        // which a RANDOM_PORT test does not have. The proxy itself is asserted over HTTP below.
        "app.ister.s3.ffmpeg-direct=true",
        // The cluster-shared S3 cache: derived files (the episode still) land in the bucket too.
        "app.ister.server.cache-s3-connection=minio",
        "app.ister.server.cache-s3-prefix=cache",
        // The cluster-shared HLS tmp store: playlists and segments are published to the bucket too.
        "app.ister.server.tmp-s3-connection=minio",
        "app.ister.server.tmp-s3-prefix=tmp",
})
@Testcontainers(disabledWithoutDocker = true)
class S3LibraryScanIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(DockerImageName
            .parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z").asCompatibleSubstituteFor("minio/minio"));

    private static final String BUCKET = "ister";
    private static final String MKV_KEY = "media/shows/Show (2024)/Season 01/s01e01.mkv";
    private static final String COVER_KEY = "media/shows/Show (2024)/cover.jpg";

    @DynamicPropertySource
    static void s3Connection(DynamicPropertyRegistry registry) {
        registry.add("app.ister.s3.connections[0].name", () -> "minio");
        registry.add("app.ister.s3.connections[0].endpoint", MINIO::getS3URL);
        registry.add("app.ister.s3.connections[0].bucket", () -> BUCKET);
        registry.add("app.ister.s3.connections[0].access-key", MINIO::getUserName);
        registry.add("app.ister.s3.connections[0].secret-key", MINIO::getPassword);
    }

    @Autowired private MessageSender messageSender;
    @Autowired private DirectoryRepository directoryRepository;
    @Autowired private MediaFileRepository mediaFileRepository;
    @Autowired private ShowRepository showRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private ImageRepository imageRepository;
    @Autowired private NodeTokenManager nodeTokenManager;
    @Autowired private app.ister.core.service.StreamTokenService streamTokenService;

    @LocalServerPort
    private int port;

    private static long mkvSize;

    @BeforeAll
    static void seedBucket() throws Exception {
        Path mkv = Path.of("..", "disk", "src", "test", "resources", "eventHandlers", "mediaFileFound", "test.mkv");
        mkvSize = Files.size(mkv);
        try (S3Client client = s3Client()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(MKV_KEY).build(), RequestBody.fromFile(mkv));
            client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(COVER_KEY).contentType("image/jpeg").build(),
                    RequestBody.fromBytes("not-really-a-jpeg".getBytes(StandardCharsets.UTF_8)));
            client.putObject(PutObjectRequest.builder().bucket(BUCKET).key("media/movies/Other (2020)/Other (2020).mkv").build(),
                    RequestBody.fromBytes(new byte[]{1}));
        }
    }

    @Test
    void s3DirectoryIsScannedAnalyzedAndProxiedWithRanges() throws Exception {
        DirectoryEntity directory = directoryRepository.findByName("it-shows-s3").orElseThrow();
        assertEquals(StorageKind.S3, directory.getStorageKind());
        assertNull(directory.getNodeEntity(), "an S3 directory has no owning node");
        assertEquals("s3://ister/media/shows", directory.getPath());
        assertEquals("media/shows", directory.getS3Prefix());
        assertEquals(1, directoryRepository.findAttachedNodes(directory.getId()).size(), "this node is attached");
        assertEquals(DirectoryType.LIBRARY, directory.getDirectoryType());

        messageSender.sendNewDirectoriesScanRequested(NewDirectoriesScanRequestedData.builder()
                .eventType(EventType.NEW_DIRECTORIES_SCAN_REQUEST)
                .directoryEntityUUID(directory.getId())
                .build(), directory.getName());

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<MediaFileEntity> files = mediaFileRepository.findByDirectoryEntity(directory);
            assertEquals(1, files.size(), "only the object under the show prefix is scanned");
            assertEquals("s3://ister/" + MKV_KEY, files.getFirst().getPath());
        });
        MediaFileEntity mediaFile = mediaFileRepository.findByDirectoryEntity(directory).getFirst();
        assertEquals(mkvSize, mediaFile.getSize());
        assertEquals(1, showRepository.findAll().size());
        assertEquals(1, episodeRepository.findAll().size());

        // MEDIA_FILE_FOUND probed the object (streams + duration) and IMAGE_FOUND stat'ed the cover.
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            MediaFileEntity analyzed = mediaFileRepository.findById(mediaFile.getId()).orElseThrow();
            assertTrue(analyzed.getDurationInMilliseconds() > 0, "ffprobe must have read the S3 object");
            List<ImageEntity> covers = imageRepository.findByDirectoryEntity(directory);
            assertEquals(1, covers.size());
            assertEquals("s3://ister/" + COVER_KEY, covers.getFirst().getPath());
            assertNotNull(covers.getFirst().getFileLastModifiedTime());
        });

        assertObjectProxiedWithRanges(mediaFile);
        assertCoverServed(imageRepository.findByDirectoryEntity(directory).getFirst());
        assertSharedCacheHoldsTheEpisodeStill();
        assertHlsIsPublishedToAndReadThroughFromTheSharedTmpStore(mediaFile);
    }

    /**
     * Playback over HLS: the playlists and the first segment of the direct-play pass are
     * published to the shared tmp store; after wiping the local tmp dir the same segment is
     * served again by reading it back from the store.
     */
    private void assertHlsIsPublishedToAndReadThroughFromTheSharedTmpStore(MediaFileEntity mediaFile) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        RestTemplate rest = new RestTemplate();
        String base = "http://localhost:%d/hls/%s/".formatted(port, mediaFile.getId());

        String master = rest.exchange(base + "master.m3u8?direct=true&transcode=false", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();
        assertNotNull(master);
        String streamPlaylist = java.util.Arrays.stream(master.split("\\n"))
                .filter(line -> line.startsWith("stream_video_") && line.contains(".m3u8"))
                .map(line -> line.substring(0, line.indexOf(".m3u8") + 5))
                .findFirst().orElseThrow(() -> new AssertionError("no video stream playlist in:\n" + master));
        String playlist = rest.exchange(base + streamPlaylist, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        assertNotNull(playlist);
        String segment = java.util.Arrays.stream(playlist.split("\\n"))
                .filter(line -> line.startsWith("seg_video_") && line.endsWith(".ts"))
                .findFirst().orElseThrow(() -> new AssertionError("no segment in:\n" + playlist));

        ResponseEntity<byte[]> first = rest.exchange(base + segment, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertEquals(200, first.getStatusCode().value());
        assertTrue(first.getBody().length > 0);

        // published: playlists right away, the segment once it is stable, the done marker after the pass
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<String> published = publishedTmpKeys(mediaFile);
            assertTrue(published.contains("master_direct.m3u8") || published.stream().anyMatch(k -> k.endsWith(".m3u8")), published.toString());
            assertTrue(published.contains(segment), "segment published: " + published);
            assertTrue(published.stream().anyMatch(k -> k.startsWith("done_seg_video_")), "done marker published: " + published);
        });

        // read-through: wipe the local working dir, the segment comes back from the store
        Path localDir = Path.of(System.getProperty("java.io.tmpdir"), "ister-s3-it", "tmp", mediaFile.getId().toString());
        try (var walk = Files.walk(localDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        assertTrue(!Files.exists(localDir));
        ResponseEntity<byte[]> again = rest.exchange(base + segment, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertEquals(200, again.getStatusCode().value());
        assertEquals(first.getBody().length, again.getBody().length);
        assertTrue(Files.exists(localDir.resolve(segment)), "read through into the local tmp dir");
    }

    private static List<String> publishedTmpKeys(MediaFileEntity mediaFile) {
        try (S3Client client = s3Client()) {
            String prefix = "tmp/" + mediaFile.getId() + "/";
            return client.listObjectsV2(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                            .bucket(BUCKET).prefix(prefix).build())
                    .contents().stream().map(o -> o.key().substring(prefix.length())).toList();
        }
    }

    private static S3Client s3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    /** With cache-s3-connection set, the background still ffmpeg made went into the shared S3 cache, not a local dir. */
    private void assertSharedCacheHoldsTheEpisodeStill() {
        DirectoryEntity sharedCache = directoryRepository.findByName("Test server-s3-cache").orElseThrow();
        assertEquals(StorageKind.S3, sharedCache.getStorageKind());
        assertEquals(DirectoryType.CACHE, sharedCache.getDirectoryType());
        assertEquals("s3://ister/cache", sharedCache.getPath());
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<ImageEntity> stills = imageRepository.findByDirectoryEntity(sharedCache);
            assertEquals(1, stills.size(), "the episode still is written into the shared cache");
            assertTrue(stills.getFirst().getPath().startsWith("s3://ister/cache/"), stills.getFirst().getPath());
            assertTrue(stills.getFirst().getPath().endsWith(".jpg"));
        });
        ImageEntity still = imageRepository.findByDirectoryEntity(sharedCache).getFirst();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        ResponseEntity<byte[]> response = new RestTemplate().exchange(
                "http://localhost:%d/images/%s/download".formatted(port, still.getId()),
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().length > 0);
        assertTrue(String.valueOf(response.getHeaders().getContentType()).startsWith("image/"));
    }

    /** What ffmpeg on a helper (or on this node over loopback) relies on: 206 with Content-Range. */
    private void assertObjectProxiedWithRanges(MediaFileEntity mediaFile) throws Exception {
        String token = nodeTokenManager.getDownloadToken();
        assertNotNull(token, "the node download token is issued at startup");
        assertTrue(streamTokenService.validateStreamToken(token).map(t -> t.isDownload()).orElse(false),
                "the download token must validate as a node download token");
        java.net.URI uri = java.net.URI.create("http://localhost:%d/mediaFile/%s/download?token=%s"
                .formatted(port, mediaFile.getId(), token));
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

        java.net.http.HttpResponse<byte[]> partial = client.send(java.net.http.HttpRequest.newBuilder(uri)
                .header(HttpHeaders.RANGE, "bytes=0-9").build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, partial.statusCode(), "headers: " + partial.headers().map());
        assertEquals("bytes 0-9/" + mkvSize, partial.headers().firstValue(HttpHeaders.CONTENT_RANGE).orElse(null));
        assertEquals("bytes", partial.headers().firstValue(HttpHeaders.ACCEPT_RANGES).orElse(null));
        assertEquals(10, partial.body().length);

        java.net.http.HttpResponse<byte[]> whole = client.send(java.net.http.HttpRequest.newBuilder(uri).build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, whole.statusCode());
        assertEquals(mkvSize, whole.body().length);

        java.net.http.HttpResponse<byte[]> head = client.send(java.net.http.HttpRequest.newBuilder(uri)
                .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
    }

    private void assertCoverServed(ImageEntity cover) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        ResponseEntity<byte[]> response = new RestTemplate().exchange(
                "http://localhost:%d/images/%s/download".formatted(port, cover.getId()),
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("not-really-a-jpeg", new String(response.getBody(), StandardCharsets.UTF_8));
        assertNotNull(response.getHeaders().getETag());
    }
}
