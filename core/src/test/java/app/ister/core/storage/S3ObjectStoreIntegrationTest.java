package app.ister.core.storage;

import app.ister.core.config.S3Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link S3ObjectStore} against a real MinIO: the S3-compatible path (endpoint override,
 * path-style, checksums only when required) that AWS-only code tends to get wrong.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3ObjectStoreIntegrationTest {

    @Container
    // MinIO no longer publishes to Docker Hub; the Quay image is the same server.
    static final MinIOContainer MINIO = new MinIOContainer(DockerImageName
            .parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z").asCompatibleSubstituteFor("minio/minio"));

    static S3ObjectStore store;

    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket("ister").build());
        }
        S3Properties.Connection connection = new S3Properties.Connection();
        connection.setName("minio");
        connection.setEndpoint(MINIO.getS3URL());
        connection.setBucket("ister");
        connection.setAccessKey(MINIO.getUserName());
        connection.setSecretKey(MINIO.getPassword());
        store = new S3ObjectStore(connection);
    }

    @AfterAll
    static void close() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void putStatOpenRangeAndDelete() throws IOException {
        byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        store.put("media/Show (2024)/Season 01/s01e01.mkv", new ByteArrayInputStream(bytes), bytes.length, "video/x-matroska");

        Optional<ObjectStat> stat = store.stat("media/Show (2024)/Season 01/s01e01.mkv");
        assertThat(stat).isPresent();
        assertThat(stat.get().size()).isEqualTo(10);
        assertThat(stat.get().etag()).isNotBlank();
        assertThat(stat.get().contentType()).isEqualTo("video/x-matroska");
        assertThat(store.stat("media/missing.mkv")).isEmpty();

        try (InputStream in = store.open("media/Show (2024)/Season 01/s01e01.mkv")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("0123456789");
        }
        try (RangedObject range = store.openRange("media/Show (2024)/Season 01/s01e01.mkv", 2, 5)) {
            assertThat(range.start()).isEqualTo(2);
            assertThat(range.end()).isEqualTo(5);
            assertThat(range.totalSize()).isEqualTo(10);
            assertThat(new String(range.body().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("2345");
        } catch (Exception e) {
            throw new IOException(e);
        }
        try (RangedObject range = store.openRange("media/Show (2024)/Season 01/s01e01.mkv", 7, -1)) {
            assertThat(new String(range.body().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("789");
            assertThat(range.partial()).isTrue();
        } catch (Exception e) {
            throw new IOException(e);
        }
        assertThatThrownBy(() -> store.openRange("media/Show (2024)/Season 01/s01e01.mkv", 20, 30))
                .isInstanceOf(S3ObjectStore.RangeNotSatisfiableException.class);
        assertThatThrownBy(() -> store.open("media/missing.mkv")).isInstanceOf(java.nio.file.NoSuchFileException.class);

        assertThat(store.delete("media/Show (2024)/Season 01/s01e01.mkv")).isTrue();
        assertThat(store.delete("media/Show (2024)/Season 01/s01e01.mkv")).isFalse();
    }

    @Test
    void shallowListingSeparatesObjectsFromChildPrefixesAndRecursiveListingPaginates() throws IOException {
        for (int i = 0; i < 1005; i++) {
            store.put("many/file-%04d.txt".formatted(i), new ByteArrayInputStream(new byte[]{1}), 1, null);
        }
        store.put("tree/a.txt", new ByteArrayInputStream(new byte[]{1}), 1, null);
        store.put("tree/sub/b.txt", new ByteArrayInputStream(new byte[]{1}), 1, null);
        store.put("tree/sub/deeper/c.txt", new ByteArrayInputStream(new byte[]{1}), 1, null);

        ObjectStore.Listing listing = store.listShallow("tree/");
        assertThat(listing.objects()).extracting(ObjectStat::key).containsExactly("tree/a.txt");
        assertThat(listing.childPrefixes()).containsExactly("tree/sub");
        assertThat(store.listShallow("tree/sub/").childPrefixes()).containsExactly("tree/sub/deeper");

        try (var all = store.list("many/")) {
            assertThat(all.count()).isEqualTo(1005);
        }
        assertThat(store.listShallow("").childPrefixes()).contains("many", "tree");
    }

    @Test
    void copyToLocalAndPresignedUrlDeliverTheObject() throws IOException {
        byte[] bytes = "cover".getBytes(StandardCharsets.UTF_8);
        store.put("art/cover.jpg", new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        Path target = Files.createTempDirectory("s3-it").resolve("nested").resolve("cover.jpg");

        store.copyToLocal("art/cover.jpg", target);
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes);

        String url = store.presignGet("art/cover.jpg", Duration.ofMinutes(5));
        assertThat(url).contains("X-Amz-Signature");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        assertThat(connection.getResponseCode()).isEqualTo(200);
        try (InputStream in = connection.getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }

        Path local = Files.createTempFile("s3-it", ".bin");
        Files.write(local, List.of("from-file"));
        store.put("art/from-file.txt", local, "text/plain");
        assertThat(store.stat("art/from-file.txt")).isPresent();
    }
}
