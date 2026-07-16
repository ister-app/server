package app.ister.server;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.NewDirectoriesScanRequestedData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.service.MessageSender;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end comic flow against real PostgreSQL and RabbitMQ: a COMIC library (series-first
 * layout) holding a cbz with embedded ComicInfo.xml and a pdf of another volume is scanned
 * through the full pipeline — scan → COMIC_FILE_FOUND → page counts, ComicInfo metadata, covers —
 * and the comic endpoints then serve the manifest, a page image and a ranged chunk of the pdf.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-comic-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-comic-it/cache/",
        "app.ister.disk.libraries[0].name=it-comics",
        "app.ister.disk.libraries[0].type=COMIC",
        "app.ister.disk.directories[0].name=it-comic-disk",
        // No trailing slash: AnalyzerSimpleFileVisitor compares the walked root against this
        // path with String equality, and Path.walk hands out the root without the slash.
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-comic-it/media",
        "app.ister.disk.directories[0].library=it-comics",
})
@Testcontainers(disabledWithoutDocker = true)
class ComicLibraryScanIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    @Autowired
    private MessageSender messageSender;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private SeriesRepository seriesRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private MediaFileRepository mediaFileRepository;

    @LocalServerPort
    private int port;

    // A 1x1 px jpg would need real image bytes for rendering clients, but the server only zips and
    // serves them; arbitrary bytes suffice.
    private static final byte[] PAGE_ONE = {10, 20, 30};
    private static final byte[] PAGE_TWO = {40, 50, 60};

    @BeforeAll
    static void createComicLibrary() throws IOException {
        Path seriesDir = Path.of(System.getProperty("java.io.tmpdir"), "ister-comic-it", "media", "Fairy Tail (2006)");
        Files.createDirectories(seriesDir);
        writeCbz(seriesDir.resolve("fairytail_vol12.cbz"));
        writePdf(seriesDir.resolve("fairytail_vol1.pdf"));
        // A duplicate re-download of vol 1: must converge on the same volume row.
        writePdf(seriesDir.resolve("fairytail_vol1-1.pdf"));
    }

    private static void writeCbz(Path cbz) throws IOException {
        String comicInfo = """
                <?xml version="1.0"?>
                <ComicInfo>
                  <Number>12</Number>
                  <Title>The Guild</Title>
                  <Summary>A wizard guild.</Summary>
                  <Year>2008</Year>
                </ComicInfo>
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
            zip.putNextEntry(new ZipEntry("page10.jpg"));
            zip.write(PAGE_TWO);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("page2.jpg"));
            zip.write(PAGE_ONE);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ComicInfo.xml"));
            zip.write(comicInfo.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void writePdf(Path pdf) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
    }

    @Test
    void scanningAComicLibraryCreatesTheSeriesAndServesPagesAndRanges() {
        DirectoryEntity directory = java.util.stream.StreamSupport
                .stream(directoryRepository.findAll().spliterator(), false)
                .filter(dir -> dir.getDirectoryType() == DirectoryType.LIBRARY)
                .findFirst().orElseThrow();
        messageSender.sendNewDirectoriesScanRequested(NewDirectoriesScanRequestedData.builder()
                .eventType(EventType.NEW_DIRECTORIES_SCAN_REQUEST)
                .directoryEntityUUID(directory.getId())
                .build(), directory.getName());

        // The scan runs through RabbitMQ: FILE_SCAN_REQUESTED → ComicScanner → COMIC_FILE_FOUND.
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertEquals(1, seriesRepository.findAll().size(), "one series directory → one series");
            assertEquals(2, bookRepository.findAll().size(),
                    "vol12.cbz + vol1.pdf + its -1 duplicate → two volumes");
        });

        SeriesEntity series = seriesRepository.findAll().getFirst();
        assertEquals("Fairy Tail", series.getName());
        assertEquals(2006, series.getStartYear());
        assertNull(series.getPersonEntity(), "comic series carry no author");

        BookEntity vol1 = volumeByName("fairytail_vol1");
        assertEquals(1.0, vol1.getSeriesIndex());
        assertNull(vol1.getPersonEntity(), "comic volumes carry no author");
        // The "-1" re-download is a second FILE of the SAME volume — never a second volume row.
        assertEquals(2, mediaFileRepository.findByBookEntityId(vol1.getId()).size(),
                "vol1.pdf and its -1 duplicate both attach to the one volume");

        // The async content handler fills page counts and ComicInfo refinements.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            MediaFileEntity pdf = mediaFileRepository.findByBookEntityId(volumeByName("fairytail_vol1").getId()).getFirst();
            assertEquals(3, pdf.getPageCount(), "pdf page count");
            MediaFileEntity cbz = mediaFileRepository.findByBookEntityId(volumeByName("fairytail_vol12").getId()).getFirst();
            assertEquals(2, cbz.getPageCount(), "cbz image count");
        });
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            BookEntity vol12 = volumeByName("fairytail_vol12");
            assertEquals("The Guild", vol12.getTitle(), "ComicInfo.xml title wins over the filename");
            assertEquals(12.0, vol12.getSeriesIndex());
        });

        // Manifest + page serving for the cbz, natural page order (page2 before page10).
        MediaFileEntity cbz = mediaFileRepository.findByBookEntityId(volumeByName("fairytail_vol12").getId()).getFirst();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        RestTemplate rest = new RestTemplate();

        ResponseEntity<String> manifest = rest.exchange(
                "http://localhost:%d/comic/%s/manifest".formatted(port, cbz.getId()),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(200, manifest.getStatusCode().value());
        assertTrue(manifest.getBody().contains("\"format\":\"CBZ\""));
        assertTrue(manifest.getBody().indexOf("page2.jpg") < manifest.getBody().indexOf("page10.jpg"),
                "pages must be naturally sorted");

        ResponseEntity<byte[]> page = rest.exchange(
                "http://localhost:%d/comic/%s/page/0".formatted(port, cbz.getId()),
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertEquals(200, page.getStatusCode().value());
        assertEquals(List.of(PAGE_ONE.length), List.of(page.getBody().length));

        // Ranged read of the pdf (pdf.js style).
        MediaFileEntity pdf = mediaFileRepository.findByBookEntityId(volumeByName("fairytail_vol1").getId()).getFirst();
        HttpHeaders rangeHeaders = new HttpHeaders();
        rangeHeaders.setBearerAuth("test-token");
        rangeHeaders.set(HttpHeaders.RANGE, "bytes=0-99");
        ResponseEntity<byte[]> chunk = rest.exchange(
                "http://localhost:%d/comic/%s/file".formatted(port, pdf.getId()),
                HttpMethod.GET, new HttpEntity<>(rangeHeaders), byte[].class);
        assertEquals(206, chunk.getStatusCode().value());
        assertEquals(100, chunk.getBody().length);
        assertTrue(new String(chunk.getBody(), 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }

    private BookEntity volumeByName(String name) {
        return bookRepository.findAll().stream()
                .filter(volume -> name.equals(volume.getName()))
                .findFirst().orElseThrow();
    }
}
