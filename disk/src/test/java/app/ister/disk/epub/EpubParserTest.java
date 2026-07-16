package app.ister.disk.epub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpubParserTest {

    private final EpubParser parser = new EpubParser();

    @TempDir
    Path tempDir;

    private static final String CONTAINER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """;

    private static String opf(String extraMetadata, String extraManifest) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:isbn:123</dc:identifier>
                    <dc:title>Dit zijn de namen</dc:title>
                    <dc:creator>Tommy Wieringa</dc:creator>
                    <dc:language>nl</dc:language>
                    <dc:date>2012-10-04</dc:date>
                    <dc:description>Een roman.</dc:description>
                    %s
                  </metadata>
                  <manifest>
                    <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    %s
                    <item id="chapter1" href="chapter_001.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="chapter1"/></spine>
                </package>
                """.formatted(extraMetadata, extraManifest);
    }

    private Path writeEpub(String opfContent) throws IOException {
        return writeEpub(opfContent, Map.of());
    }

    private Path writeEpub(String opfContent, Map<String, byte[]> extraEntries) throws IOException {
        Path epub = tempDir.resolve("test-" + System.nanoTime() + ".epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
            put(zip, "mimetype", "application/epub+zip".getBytes(StandardCharsets.UTF_8));
            put(zip, "META-INF/container.xml", CONTAINER.getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/content.opf", opfContent.getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/images/cover.jpg", new byte[]{1, 2, 3});
            for (Map.Entry<String, byte[]> entry : extraEntries.entrySet()) {
                put(zip, entry.getKey(), entry.getValue());
            }
        }
        return epub;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    @Test
    void parsesPlainEpubMetadata() throws IOException {
        Optional<EpubInfo> info = parser.parse(writeEpub(opf("", "")));

        assertTrue(info.isPresent());
        assertEquals("Dit zijn de namen", info.get().title());
        assertEquals("Tommy Wieringa", info.get().author());
        assertEquals("nl", info.get().language());
        assertEquals("Een roman.", info.get().description());
        assertEquals(2012, info.get().releaseYear());
        assertEquals("OEBPS/images/cover.jpg", info.get().coverEntry());
        assertFalse(info.get().mediaOverlays());
        assertEquals(0, info.get().durationInMilliseconds());
    }

    /** A media-overlay epub is recognized from its contents, never from the filename. */
    @Test
    void detectsMediaOverlaysFromContentsWithoutKaraokeInName() throws IOException {
        String metadata = "<meta property=\"media:duration\">07:53:38</meta>";
        String manifest = """
                <item id="smil1" href="smil/chapter_001.smil" media-type="application/smil+xml"/>
                <item id="audio1" href="audio/chapter_001.mp3" media-type="audio/mpeg"/>
                """;
        Optional<EpubInfo> info = parser.parse(writeEpub(opf(metadata, manifest)));

        assertTrue(info.isPresent());
        assertTrue(info.get().mediaOverlays());
        assertEquals(((7 * 60 + 53) * 60 + 38) * 1000L, info.get().durationInMilliseconds());
    }

    @Test
    void detectsMediaOverlaysFromMediaOverlayAttribute() throws IOException {
        String manifest = "<item id=\"c2\" href=\"chapter_002.xhtml\" media-type=\"application/xhtml+xml\" media-overlay=\"smil2\"/>";
        Optional<EpubInfo> info = parser.parse(writeEpub(opf("", manifest)));

        assertTrue(info.isPresent());
        assertTrue(info.get().mediaOverlays());
    }

    @Test
    void epub2CoverMetaFallback() throws IOException {
        String opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:isbn:123</dc:identifier>
                    <dc:title>Old Book</dc:title>
                    <meta name="cover" content="cover-img"/>
                  </metadata>
                  <manifest>
                    <item id="cover-img" href="images/cover.jpg" media-type="image/jpeg"/>
                  </manifest>
                  <spine/>
                </package>
                """;
        Optional<EpubInfo> info = parser.parse(writeEpub(opfContent));

        assertTrue(info.isPresent());
        assertEquals("OEBPS/images/cover.jpg", info.get().coverEntry());
    }

    @Test
    void readsEntryBytes() throws IOException {
        Path epub = writeEpub(opf("", ""));
        Optional<byte[]> bytes = parser.readEntry(epub, "OEBPS/images/cover.jpg");

        assertTrue(bytes.isPresent());
        assertArrayEquals(new byte[]{1, 2, 3}, bytes.get());
        assertTrue(parser.readEntry(epub, "OEBPS/missing.jpg").isEmpty());
    }

    @Test
    void invalidEpubReturnsEmpty() throws IOException {
        Path notAnEpub = tempDir.resolve("broken.epub");
        Files.writeString(notAnEpub, "not a zip");
        assertTrue(parser.parse(notAnEpub).isEmpty());
    }

    // ===== ISBN =====

    @Test
    void parsesAnUrnIsbnIdentifier() throws IOException {
        String metadata = "<dc:identifier xmlns:dc=\"http://purl.org/dc/elements/1.1/\">urn:isbn:978-90-257-4785-5</dc:identifier>";
        Optional<EpubInfo> info = parser.parse(writeEpub(opf(metadata, "")));

        assertEquals("9789025747855", info.orElseThrow().isbn());
    }

    @Test
    void parsesAnOpfSchemeIsbnIdentifier() throws IOException {
        String metadata = """
                <dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/"
                               xmlns:opf="http://www.idpf.org/2007/opf"
                               opf:scheme="ISBN">90-257-4785-X</dc:identifier>
                """;
        Optional<EpubInfo> info = parser.parse(writeEpub(opf(metadata, "")));

        assertEquals("902574785X", info.orElseThrow().isbn());
    }

    @Test
    void parsesAPlainSchemeAttributeAndBareIsbnText() throws IOException {
        String metadata = "<dc:identifier xmlns:dc=\"http://purl.org/dc/elements/1.1/\" scheme=\"isbn\">9789025747855</dc:identifier>";
        Optional<EpubInfo> info = parser.parse(writeEpub(opf(metadata, "")));

        assertEquals("9789025747855", info.orElseThrow().isbn());
    }

    /** A schemeless identifier counts only when its text already looks like an ISBN. */
    @Test
    void schemelessIdentifierThatIsNoIsbnGivesNull() throws IOException {
        // The fixture's own uid identifier is "urn:isbn:123" — too short to be an ISBN.
        Optional<EpubInfo> info = parser.parse(writeEpub(opf(
                "<dc:identifier xmlns:dc=\"http://purl.org/dc/elements/1.1/\">uuid:1234-5678</dc:identifier>", "")));

        assertEquals(null, info.orElseThrow().isbn());
    }

    @Test
    void normalizesIsbnValues() {
        assertEquals("9789025747855", EpubParser.normalizeIsbn("urn:isbn:978 90 257 47855"));
        assertEquals("902574785X", EpubParser.normalizeIsbn("90-257-4785-x"));
        assertEquals(null, EpubParser.normalizeIsbn("123"));
        assertEquals(null, EpubParser.normalizeIsbn("not-an-isbn"));
    }

    // ===== Series =====

    @Test
    void parsesEpub3BelongsToCollectionWithGroupPosition() throws IOException {
        String metadata = """
                <meta property="belongs-to-collection" id="c01">De Grijze Jager</meta>
                <meta refines="#c01" property="collection-type">series</meta>
                <meta refines="#c01" property="group-position">3</meta>
                """;
        Optional<EpubInfo> info = parser.parse(writeEpub(opf(metadata, "")));

        assertEquals("De Grijze Jager", info.orElseThrow().seriesName());
        assertEquals(3.0, info.get().seriesIndex());
    }

    @Test
    void parsesCalibreSeriesMetas() throws IOException {
        String metadata = """
                <meta name="calibre:series" content="De Grijze Jager"/>
                <meta name="calibre:series_index" content="1.5"/>
                """;
        Optional<EpubInfo> info = parser.parse(writeEpub(opf(metadata, "")));

        assertEquals("De Grijze Jager", info.orElseThrow().seriesName());
        assertEquals(1.5, info.get().seriesIndex());
    }

    @Test
    void epub3SeriesWinsOverCalibreSeries() throws IOException {
        String metadata = """
                <meta property="belongs-to-collection" id="c01">Ranger's Apprentice</meta>
                <meta name="calibre:series" content="De Grijze Jager"/>
                """;
        Optional<EpubInfo> info = parser.parse(writeEpub(opf(metadata, "")));

        assertEquals("Ranger's Apprentice", info.orElseThrow().seriesName());
        assertEquals(null, info.get().seriesIndex());
    }

    @Test
    void unparseableSeriesIndexKeepsTheSeriesName() throws IOException {
        String metadata = """
                <meta name="calibre:series" content="De Grijze Jager"/>
                <meta name="calibre:series_index" content="one"/>
                """;
        Optional<EpubInfo> info = parser.parse(writeEpub(opf(metadata, "")));

        assertEquals("De Grijze Jager", info.orElseThrow().seriesName());
        assertEquals(null, info.get().seriesIndex());
    }

    @Test
    void epubWithoutSeriesMetadataGivesNulls() throws IOException {
        Optional<EpubInfo> info = parser.parse(writeEpub(opf("", "")));

        assertEquals(null, info.orElseThrow().seriesName());
        assertEquals(null, info.get().seriesIndex());
    }

    @Test
    void parsesClockValues() {
        assertEquals(6317, EpubParser.parseClockValue("6.317s"));
        assertEquals(1500, EpubParser.parseClockValue("1500ms"));
        assertEquals(3723500, EpubParser.parseClockValue("1:02:03.500"));
        assertEquals(0, EpubParser.parseClockValue(null));
        assertEquals(0, EpubParser.parseClockValue("abc"));
    }
}
