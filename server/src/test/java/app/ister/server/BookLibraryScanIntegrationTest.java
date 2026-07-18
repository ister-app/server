package app.ister.server;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.NewDirectoriesScanRequestedData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.PersonRepository;
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
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end book flow against real PostgreSQL and RabbitMQ: a BOOK library holding an epub
 * (with media overlays, deliberately WITHOUT any "karaoke" naming) is scanned through the full
 * event pipeline — scan → EPUB_FILE_FOUND → OPF parsing → book/author/metadata/cover rows —
 * and the epub resource endpoint then serves an individual chapter file from inside the zip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-book-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-book-it/cache/",
        "app.ister.disk.libraries[0].name=it-books",
        "app.ister.disk.libraries[0].type=BOOK",
        "app.ister.disk.directories[0].name=it-book-disk",
        // No trailing slash: AnalyzerSimpleFileVisitor compares the walked root against this
        // path with String equality, and Path.walk hands out the root without the slash.
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-book-it/media",
        "app.ister.disk.directories[0].library=it-books",
})
@Testcontainers(disabledWithoutDocker = true)
class BookLibraryScanIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    // No own JwtDecoder stub: GraphQlSubscriptionIntegrationTest.FakeJwtConfig (same package)
    // is picked up by the component scan and already accepts any bearer token with roles
    // "user"+"admin"; a second nested @TestConfiguration with the same bean name breaks AOT
    // processing.

    @Autowired
    private MessageSender messageSender;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private MetadataRepository metadataRepository;
    @Autowired
    private ImageRepository imageRepository;

    @LocalServerPort
    private int port;

    private static final String CHAPTER_XHTML =
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p id=\"s1\">Hello book.</p></body></html>";

    @BeforeAll
    static void createBookLibrary() throws IOException {
        Path authorDir = Path.of(System.getProperty("java.io.tmpdir"), "ister-book-it", "media", "Owl (1950)");
        Files.createDirectories(authorDir);
        writeEpub(authorDir.resolve("Night Flight (2015).epub"));

        // A second author with two books sharing a "Series - Title" prefix: the first carries
        // calibre series metadata (epub source), the second has none (path-prefix heuristic).
        Path seriesAuthorDir = Path.of(System.getProperty("java.io.tmpdir"), "ister-book-it", "media", "Falcon");
        Files.createDirectories(seriesAuthorDir);
        writeSeriesEpub(seriesAuthorDir.resolve("Sky Rangers - First Flight.epub"),
                "Sky Rangers - First Flight",
                "<meta name=\"calibre:series\" content=\"Sky Rangers\"/>"
                        + "<meta name=\"calibre:series_index\" content=\"1.0\"/>");
        writeSeriesEpub(seriesAuthorDir.resolve("Sky Rangers - Second Flight.epub"),
                "Sky Rangers - Second Flight", "");
    }

    private static void writeSeriesEpub(Path epub, String title, String seriesMetadata) throws IOException {
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
                    <dc:identifier id="uid">urn:uuid:%s</dc:identifier>
                    <dc:title>%s</dc:title>
                    <dc:creator>Falcon</dc:creator>
                    <dc:language>en</dc:language>
                    <dc:date>2008-01-01</dc:date>
                    <dc:description>A series book.</dc:description>
                    %s
                  </metadata>
                  <manifest>
                    <item id="c1" href="chapter_1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
                """.formatted(title, title, seriesMetadata);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", container);
            put(zip, "OEBPS/content.opf", opf);
            put(zip, "OEBPS/chapter_1.xhtml", CHAPTER_XHTML);
        }
    }

    /** Minimal EPUB 3 with a SMIL media overlay; overlay detection must come from this content. */
    private static void writeEpub(Path epub) throws IOException {
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
                    <dc:identifier id="uid">urn:uuid:it-book</dc:identifier>
                    <dc:title>Night Flight</dc:title>
                    <dc:creator>Owl</dc:creator>
                    <dc:language>en</dc:language>
                    <dc:date>2015-05-01</dc:date>
                    <dc:description>An integration test book.</dc:description>
                    <meta property="media:duration">0:00:10.000</meta>
                  </metadata>
                  <manifest>
                    <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    <item id="c1" href="chapter_1.xhtml" media-type="application/xhtml+xml" media-overlay="smil1"/>
                    <item id="smil1" href="chapter_1.smil" media-type="application/smil+xml"/>
                    <item id="a1" href="audio/chapter_1.mp3" media-type="audio/mpeg"/>
                  </manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
                """;
        String smil = """
                <?xml version="1.0" encoding="UTF-8"?>
                <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
                  <body><seq><par><text src="chapter_1.xhtml#s1"/><audio src="audio/chapter_1.mp3" clipBegin="0s" clipEnd="10s"/></par></seq></body>
                </smil>
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", container);
            put(zip, "OEBPS/content.opf", opf);
            put(zip, "OEBPS/chapter_1.xhtml", CHAPTER_XHTML);
            put(zip, "OEBPS/chapter_1.smil", smil);
            put(zip, "OEBPS/cover.jpg", "fake-cover-bytes");
            put(zip, "OEBPS/audio/chapter_1.mp3", "fake-audio-bytes");
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void scanningABookLibraryCreatesTheBookAndServesEpubResources() {
        DirectoryEntity directory = java.util.stream.StreamSupport
                .stream(directoryRepository.findAll().spliterator(), false)
                .filter(dir -> dir.getDirectoryType() == DirectoryType.LIBRARY)
                .findFirst().orElseThrow();
        messageSender.sendNewDirectoriesScanRequested(NewDirectoriesScanRequestedData.builder()
                .eventType(EventType.NEW_DIRECTORIES_SCAN_REQUEST)
                .directoryEntityUUID(directory.getId())
                .build(), directory.getName());

        // The scan runs through RabbitMQ: FILE_SCAN_REQUESTED → EpubScanner → EPUB_FILE_FOUND.
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<BookEntity> books = bookRepository.findAll();
            assertEquals(3, books.size(), "the three epubs should have created three books");
        });

        BookEntity book = bookRepository.findAll().stream()
                .filter(b -> "Night Flight".equals(b.getName())).findFirst().orElseThrow();
        assertEquals("Night Flight", book.getName());
        assertEquals(2015, book.getPathYear(), "the year from the \"(YYYY)\" suffix is scanner identity");
        assertEquals(2015, book.getReleaseYear());
        var author = personRepository.findById(book.getPersonEntity().getId()).orElseThrow();
        assertEquals("Owl", author.getName());
        assertEquals(1950, author.getBirthYear());

        // HandleEpubFileFound: overlay flag from the CONTENT (no "karaoke" in the filename),
        // duration from media:duration, OPF metadata and the extracted cover.
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<MediaFileEntity> files = mediaFileRepository.findByBookEntityId(book.getId());
            assertEquals(1, files.size());
            assertEquals(Boolean.TRUE, files.getFirst().getMediaOverlays(),
                    "media overlays must be detected from the epub contents");
            assertEquals(10_000, files.getFirst().getDurationInMilliseconds());
        });
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertFalse(metadataRepository.findByBookEntityId(book.getId()).isEmpty(), "OPF metadata should be stored");
            assertFalse(imageRepository.findByBookEntityId(book.getId()).isEmpty(), "cover should be extracted");
        });
        var metadata = metadataRepository.findByBookEntityId(book.getId()).getFirst();
        assertEquals("Night Flight", metadata.getTitle());
        assertEquals("An integration test book.", metadata.getDescription());
        assertEquals("eng", metadata.getLanguage());
        assertEquals(java.time.LocalDate.of(2015, 1, 1), metadata.getReleased(),
                "the epub's dc:date year must be persisted");

        // Series: the calibre-tagged epub assigns book one; the prefix heuristic pulls in book
        // two. Both end up under one series with the prefix stripped from their display title.
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            BookEntity first = bookRepository.findAll().stream()
                    .filter(b -> "Sky Rangers - First Flight".equals(b.getName())).findFirst().orElseThrow();
            BookEntity second = bookRepository.findAll().stream()
                    .filter(b -> "Sky Rangers - Second Flight".equals(b.getName())).findFirst().orElseThrow();
            assertNotNull(first.getSeriesEntity(), "epub series metadata must link the series");
            assertNotNull(second.getSeriesEntity(), "the prefix heuristic must link the sibling");
            assertEquals("Sky Rangers", first.getSeriesEntity().getName());
            assertEquals(first.getSeriesEntity().getId(), second.getSeriesEntity().getId());
            assertEquals("First Flight", first.getTitle());
            assertEquals("Second Flight", second.getTitle());
            assertEquals(1.0, first.getSeriesIndex());
        });

        // Lazy reading: fetch one chapter file from inside the epub over HTTP.
        MediaFileEntity epubFile = mediaFileRepository.findByBookEntityId(book.getId()).getFirst();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        ResponseEntity<String> response = new RestTemplate().exchange(
                "http://localhost:%d/epub/%s/resource/OEBPS/chapter_1.xhtml".formatted(port, epubFile.getId()),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(CHAPTER_XHTML, response.getBody());
        assertNotNull(response.getHeaders().getETag());
        assertTrue(String.valueOf(response.getHeaders().getContentType()).contains("xhtml"));
    }
}
