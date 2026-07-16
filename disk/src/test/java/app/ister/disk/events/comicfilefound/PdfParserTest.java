package app.ister.disk.events.comicfilefound;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfParserTest {

    private final PdfParser parser = new PdfParser();

    @TempDir
    Path tempDir;

    private Path writePdf(int pages) throws IOException {
        Path pdf = tempDir.resolve("test-" + System.nanoTime() + ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void countsThePages() throws IOException {
        assertEquals(3, parser.pageCount(writePdf(3)));
    }

    @Test
    void brokenPdfHasZeroPages() throws IOException {
        Path notAPdf = tempDir.resolve("broken.pdf");
        Files.writeString(notAPdf, "not a pdf");

        assertEquals(0, parser.pageCount(notAPdf));
    }

    /** JVM smoke test; on a native image without AWT this degrades to empty instead of throwing. */
    @Test
    void rendersPageOneAsJpegBytes() throws IOException {
        Optional<byte[]> cover = parser.renderCoverJpeg(writePdf(1));

        assertTrue(cover.isPresent());
        assertTrue(cover.get().length > 0);
    }

    @Test
    void renderOfABrokenPdfIsEmptyNotAnException() throws IOException {
        Path notAPdf = tempDir.resolve("broken2.pdf");
        Files.writeString(notAPdf, "not a pdf");

        assertTrue(parser.renderCoverJpeg(notAPdf).isEmpty());
    }
}
