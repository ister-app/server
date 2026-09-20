package app.ister.server;

import app.ister.core.entity.BookEntity;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.storage.LibraryWriteStore;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin upload end to end, over real HTTP against the full application with real PostgreSQL
 * and RabbitMQ: directory picker → preview → session → chunk → complete, and then — WITHOUT any
 * library scan — the uploaded epub turns into a book through the normal event pipeline. That last
 * step is the point of the feature: an upload is not done when the bytes are on disk, but when the
 * library shows them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-upload-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-upload-it/cache/",
        "app.ister.disk.libraries[0].name=it-upload-books",
        "app.ister.disk.libraries[0].type=BOOK",
        "app.ister.disk.directories[0].name=it-upload-disk",
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-upload-it/media",
        "app.ister.disk.directories[0].library=it-upload-books",
        // the test machine's tmp volume need not have 5 GB to spare
        "app.ister.upload.min-free-space=1MB",
})
@Testcontainers(disabledWithoutDocker = true)
class LibraryUploadIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    // The JwtDecoder stub comes from GraphQlSubscriptionIntegrationTest.FakeJwtConfig (same
    // package, component scan): any bearer token is an admin.

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path MEDIA = Path.of(System.getProperty("java.io.tmpdir"), "ister-upload-it", "media");

    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private MediaFileRepository mediaFileRepository;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void emptyLibrary() throws IOException {
        if (Files.isDirectory(MEDIA)) {
            try (var walk = Files.walk(MEDIA)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.createDirectories(MEDIA);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer test-token");
    }

    private JsonNode json(HttpResponse<String> response) {
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> postJson(String path, Object body) throws Exception {
        return http.send(request(path).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anUploadedEpubBecomesABookWithoutAScan() throws Exception {
        byte[] epub = epub("Night Flight", "Owl");

        // 1. the picker knows the directory, that it is writable and which node to talk to
        HttpResponse<String> directories = http.send(request("/library-upload/directories").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, directories.statusCode(), directories.body());
        JsonNode directory = json(directories).get(0);
        assertEquals("it-upload-disk", directory.get("name").asText());
        assertEquals("BOOK", directory.get("libraryType").asText());
        assertTrue(directory.get("writable").asBoolean());
        assertTrue(directory.get("freeBytes").asLong() > 0);
        assertFalse(directory.get("nodeUrl").asText().isBlank());
        String directoryId = directory.get("id").asText();

        // 2. the preview speaks for the scanner
        Map<String, Object> plan = Map.of("directoryId", directoryId, "targetParent", "", "rootName", "Owl (1950)",
                "overwrite", false, "entries", List.of(
                        Map.of("relativePath", "Night Flight (2015).epub", "size", epub.length),
                        Map.of("relativePath", "notes.txt", "size", 3)));
        HttpResponse<String> previewResponse = postJson("/library-upload/preview", plan);
        assertEquals(200, previewResponse.statusCode(), previewResponse.body());
        JsonNode preview = json(previewResponse);
        assertEquals("ARTIST", preview.at("/roots/0/level").asText(), "an author folder is the book layout's artist level");
        assertEquals("RECOGNISED", preview.at("/entries/0/status").asText());
        assertEquals("Owl", preview.at("/entries/0/recognition/author").asText());
        assertEquals("Night Flight", preview.at("/entries/0/recognition/book").asText());
        assertEquals("IGNORED", preview.at("/entries/1/status").asText());
        assertEquals(1, preview.get("uploadFiles").asInt());

        // 3. a session holds only what will be uploaded
        HttpResponse<String> sessionResponse = postJson("/library-upload/sessions", plan);
        assertEquals(200, sessionResponse.statusCode(), sessionResponse.body());
        JsonNode session = json(sessionResponse);
        String sessionId = session.get("sessionId").asText();
        assertEquals(1, session.get("files").size());
        assertEquals("notes.txt", session.at("/skipped/0/relativePath").asText());
        String fileId = session.at("/files/0/fileId").asText();
        String files = "/library-upload/sessions/" + sessionId + "/files/" + fileId;

        // 4. the chunk; sent twice, as a client does whose first response got lost
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> chunk = http.send(request(files + "/chunk?offset=0")
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(epub)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, chunk.statusCode(), chunk.body());
            assertEquals(epub.length, json(chunk).get("receivedBytes").asLong());
        }
        Path target = MEDIA.resolve("Owl (1950)").resolve("Night Flight (2015).epub");
        assertFalse(Files.exists(target), "nothing appears in the library before complete");
        assertTrue(Files.isDirectory(MEDIA.resolve(LibraryWriteStore.STAGING_DIR).resolve(sessionId)));

        // 5. complete puts it in place, ends the session and clears the staging folder
        HttpResponse<String> complete = http.send(request(files + "/complete")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, complete.statusCode(), complete.body());
        assertEquals("COMPLETED", json(complete).get("status").asText());
        assertArrayEquals(epub, Files.readAllBytes(target));
        assertFalse(Files.exists(MEDIA.resolve(LibraryWriteStore.STAGING_DIR).resolve(sessionId)));
        JsonNode state = json(http.send(request("/library-upload/sessions/" + sessionId).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        assertEquals("COMPLETED", state.get("status").asText());

        // 6. no scan was requested — the upload itself fed the pipeline:
        //    FILE_SCAN_REQUESTED → EpubScanner → EPUB_FILE_FOUND → OPF parsing
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<BookEntity> books = bookRepository.findAll();
            assertEquals(1, books.size());
            assertEquals("Night Flight", books.getFirst().getName());
        });
        assertTrue(mediaFileRepository.findPathsByDirectoryEntityIdAndPathIn(java.util.UUID.fromString(directoryId),
                List.of(target.toString())).contains(target.toString()));

        // 7. and the next preview knows it is there
        JsonNode again = json(postJson("/library-upload/preview", plan));
        assertEquals("EXISTS", again.at("/entries/0/status").asText());
        assertEquals(0, again.get("uploadFiles").asInt());
    }

    @Test
    void refusesPathsThatLeaveTheDirectoryAndCallersWithoutALogin() throws Exception {
        String directoryId = json(http.send(request("/library-upload/directories").GET().build(),
                HttpResponse.BodyHandlers.ofString())).get(0).get("id").asText();

        JsonNode preview = json(postJson("/library-upload/preview", Map.of("directoryId", directoryId,
                "targetParent", "", "overwrite", false, "entries", List.of(
                        Map.of("relativePath", "../../etc/cron.d/owned.epub", "size", 10),
                        Map.of("relativePath", ".ister-upload/sneaky.epub", "size", 10)))));
        assertEquals("INVALID", preview.at("/entries/0/status").asText());
        assertEquals("INVALID", preview.at("/entries/1/status").asText());

        assertEquals(400, postJson("/library-upload/preview", Map.of("directoryId", directoryId,
                "targetParent", "../elsewhere", "overwrite", false, "entries", List.of())).statusCode());

        // no bearer token; and a stream token in the URL is not a login here
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/library-upload/directories?token=some-stream-token"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
    }

    private static byte[] epub(String title, String author) throws IOException {
        String container = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """;
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:uuid:4f3c1b9e-upload-it</dc:identifier>
                    <dc:title>%s</dc:title>
                    <dc:creator>%s</dc:creator>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest><item id="c1" href="chapter_1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
                """.formatted(title, author);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", container);
            put(zip, "OEBPS/content.opf", opf);
            put(zip, "OEBPS/chapter_1.xhtml",
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Hello.</p></body></html>");
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
