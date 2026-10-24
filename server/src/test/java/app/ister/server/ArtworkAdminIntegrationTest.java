package app.ister.server;

import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MetadataSource;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin artwork flow over real HTTP against the full application: upload a cover for a movie,
 * see it as the selected image in {@code imageCandidates}, pick another stored image through
 * {@code selectImage}, and lose both with a forbidden call. Bearer-only like the library upload.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-artwork-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-artwork-it/cache/",
        "app.ister.disk.libraries[0].name=it-artwork-movies",
        "app.ister.disk.libraries[0].type=MOVIE",
        "app.ister.disk.directories[0].name=it-artwork-disk",
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-artwork-it/media",
        "app.ister.disk.directories[0].library=it-artwork-movies",
})
@Testcontainers(disabledWithoutDocker = true)
class ArtworkAdminIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    // The JwtDecoder stub comes from GraphQlSubscriptionIntegrationTest.FakeJwtConfig: any bearer
    // token is an admin.

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private LibraryRepository libraryRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private ImageRepository imageRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void anUploadedCoverBecomesTheSelectedImageAndCanBeOverruled() throws Exception {
        UUID movieId = new TransactionTemplate(transactionManager).execute(_ -> {
            LibraryEntity library = libraryRepository.save(LibraryEntity.builder()
                    .name("artwork-it-" + UUID.randomUUID()).libraryType(LibraryType.MOVIE).build());
            return movieRepository.save(MovieEntity.builder()
                    .libraryEntity(library).name("Painted").releaseYear(2021).build()).getId();
        });

        // 1. upload a png cover
        HttpResponse<String> upload = http.send(request("/artwork/upload?type=COVER&movieId=" + movieId)
                        .header("Content-Type", "image/png")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(png(0x3366cc))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, upload.statusCode(), upload.body());
        UUID uploadedId = UUID.fromString(MAPPER.readTree(upload.body()).get("imageId").asText());

        ImageEntity uploaded = imageRepository.findById(uploadedId).orElseThrow();
        assertTrue(uploaded.isSelected());
        assertEquals(ImageType.COVER, uploaded.getType());
        assertEquals(MetadataSource.USER, uploaded.getSource());
        assertEquals(movieId, uploaded.getMovieEntityId());
        assertTrue(uploaded.getSourceUri().startsWith("upload://"));
        assertTrue(Files.exists(Path.of(uploaded.getPath())), "stored under the cache dir: " + uploaded.getPath());
        assertTrue(uploaded.getPath().contains("/artwork/"));

        // 2. a second upload takes the slot over
        HttpResponse<String> second = http.send(request("/artwork/upload?type=COVER&movieId=" + movieId)
                        .header("Content-Type", "image/png")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(png(0xcc3366))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, second.statusCode(), second.body());
        UUID secondId = UUID.fromString(MAPPER.readTree(second.body()).get("imageId").asText());
        assertFalse(imageRepository.findById(uploadedId).orElseThrow().isSelected());
        assertTrue(imageRepository.findById(secondId).orElseThrow().isSelected());

        // 3. the candidates list both, the second selected; selectImage brings the first back
        JsonNode candidates = graphql("{ imageCandidates(target: {movieId: \\\"" + movieId + "\\\"}, type: COVER) "
                + "{ current { id selected source } remote { sourceUri } } }");
        JsonNode current = candidates.path("data").path("imageCandidates").path("current");
        assertEquals(2, current.size(), candidates.toString());
        List<String> selected = current.valueStream().filter(n -> n.get("selected").asBoolean()).map(n -> n.get("id").asText()).toList();
        assertEquals(List.of(secondId.toString()), selected);
        // no TMDB id → no remote alternatives
        assertEquals(0, candidates.path("data").path("imageCandidates").path("remote").size());

        JsonNode select = graphql("mutation { selectImage(imageId: \\\"" + uploadedId + "\\\") { id selected } }");
        assertTrue(select.path("data").path("selectImage").path("selected").asBoolean(), select.toString());
        assertFalse(imageRepository.findById(secondId).orElseThrow().isSelected());

        // 4. deleting the (now unselected) second upload is allowed, it is a USER image
        JsonNode delete = graphql("mutation { deleteImage(imageId: \\\"" + secondId + "\\\") }");
        assertTrue(delete.path("data").path("deleteImage").asBoolean(), delete.toString());
        assertTrue(imageRepository.findById(secondId).isEmpty());
    }

    @Test
    void uploadRefusesBadInputAndAnonymousCallers() throws Exception {
        UUID movieId = UUID.randomUUID();
        // two ids
        HttpResponse<String> twoIds = http.send(request("/artwork/upload?type=COVER&movieId=" + movieId + "&showId=" + movieId)
                        .header("Content-Type", "image/png")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(png(1))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, twoIds.statusCode(), twoIds.body());
        // a gif is not accepted
        HttpResponse<String> gif = http.send(request("/artwork/upload?type=COVER&movieId=" + movieId)
                        .header("Content-Type", "image/gif")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3})).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(415, gif.statusCode(), gif.body());
        // no bearer token, and a stream token in the URL is no login here
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/artwork/upload?type=COVER&movieId=" + movieId + "&token=x"))
                        .header("Content-Type", "image/png")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(png(1))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer test-token");
    }

    private JsonNode graphql(String document) throws Exception {
        HttpResponse<String> response = http.send(request("/graphql")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"" + document + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertTrue(body.path("errors").isMissingNode() || body.path("errors").isEmpty(), response.body());
        return body;
    }

    private static byte[] png(int rgb) throws IOException {
        BufferedImage image = new BufferedImage(6, 9, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 9; y++) {
                image.setRGB(x, y, rgb + x * 7 + y * 3);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
