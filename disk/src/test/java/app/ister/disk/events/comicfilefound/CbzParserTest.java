package app.ister.disk.events.comicfilefound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CbzParserTest {

    private final CbzParser parser = new CbzParser();

    @TempDir
    Path tempDir;

    private Path writeCbz(Map<String, byte[]> entries) throws IOException {
        Path cbz = tempDir.resolve("test-" + System.nanoTime() + ".cbz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return cbz;
    }

    @Test
    void pagesAreNaturallySortedAndJunkIsSkipped() throws IOException {
        Path cbz = writeCbz(Map.of(
                "page10.jpg", new byte[]{1},
                "page2.jpg", new byte[]{2},
                "page1.png", new byte[]{3},
                "__MACOSX/page1.jpg", new byte[]{4},
                ".hidden.jpg", new byte[]{5},
                "ComicInfo.xml", "<ComicInfo/>".getBytes(StandardCharsets.UTF_8),
                "notes.txt", new byte[]{6}));

        assertEquals(List.of("page1.png", "page2.jpg", "page10.jpg"), parser.pages(cbz));
    }

    @Test
    void comicInfoIsParsedCaseInsensitively() throws IOException {
        String xml = """
                <?xml version="1.0"?>
                <ComicInfo>
                  <Series>Fairy Tail</Series>
                  <Number>12</Number>
                  <Title>The Guild</Title>
                  <Summary>A wizard guild.</Summary>
                  <Year>2008</Year>
                  <Writer>Hiro Mashima</Writer>
                </ComicInfo>
                """;
        Path cbz = writeCbz(Map.of(
                "sub/comicinfo.XML", xml.getBytes(StandardCharsets.UTF_8),
                "page1.jpg", new byte[]{1}));

        Optional<ComicInfoXml> info = parser.comicInfo(cbz);

        assertTrue(info.isPresent());
        assertEquals("Fairy Tail", info.get().series());
        assertEquals(12.0, info.get().number());
        assertEquals("The Guild", info.get().title());
        assertEquals("A wizard guild.", info.get().summary());
        assertEquals(2008, info.get().year());
        assertEquals("Hiro Mashima", info.get().writer());
    }

    @Test
    void comicInfoIsEmptyWhenAbsentOrBroken() throws IOException {
        assertTrue(parser.comicInfo(writeCbz(Map.of("page1.jpg", new byte[]{1}))).isEmpty());
        assertTrue(parser.comicInfo(writeCbz(Map.of(
                "ComicInfo.xml", "not xml".getBytes(StandardCharsets.UTF_8)))).isEmpty());
    }

    @Test
    void unparseableNumberAndYearDegradeGracefully() throws IOException {
        String xml = "<ComicInfo><Series>S</Series><Number>one</Number><Year>x</Year></ComicInfo>";
        Optional<ComicInfoXml> info = parser.comicInfo(writeCbz(Map.of(
                "ComicInfo.xml", xml.getBytes(StandardCharsets.UTF_8))));

        assertTrue(info.isPresent());
        assertNull(info.get().number());
        assertEquals(0, info.get().year());
    }

    @Test
    void readEntryReturnsTheBytes() throws IOException {
        Path cbz = writeCbz(Map.of("page1.jpg", new byte[]{1, 2, 3}));

        assertArrayEquals(new byte[]{1, 2, 3}, parser.readEntry(cbz, "page1.jpg").orElseThrow());
        assertTrue(parser.readEntry(cbz, "missing.jpg").isEmpty());
    }

    @Test
    void brokenArchiveGivesNoPages() throws IOException {
        Path notAZip = tempDir.resolve("broken.cbz");
        Files.writeString(notAZip, "not a zip");

        assertTrue(parser.pages(notAZip).isEmpty());
    }
}
