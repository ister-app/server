package app.ister.disk.events.comicfilefound;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * PDF comic volumes via Apache PDFBox. The page count needs no AWT and is safe everywhere; the
 * page-1 cover render does need AWT/ImageIO, which the GraalVM native image only supports with
 * extra setup — so rendering is failure-tolerant: any error (including LinkageError from a native
 * image without AWT) degrades to "no cover" instead of poisoning the scan, the same pattern the
 * blur-hash computation uses.
 */
@Component
@Slf4j
public class PdfParser {

    private static final float COVER_DPI = 100;

    /** The number of pages, or 0 when the PDF cannot be opened. */
    public int pageCount(Path pdfPath) {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            log.warn("Could not read pdf {}: {}", pdfPath, e.getMessage());
            return 0;
        }
    }

    /** Page 1 rendered as jpg bytes, or empty when rendering is unavailable or fails. */
    public Optional<byte[]> renderCoverJpeg(Path pdfPath) {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            if (document.getNumberOfPages() == 0) {
                return Optional.empty();
            }
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, COVER_DPI);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpg", out)) {
                return Optional.empty();
            }
            return Optional.of(out.toByteArray());
        } catch (Throwable t) {
            // Throwable on purpose: a native image without AWT throws LinkageError/
            // ExceptionInInitializerError, and a broken PDF must never poison the scan either.
            log.warn("Could not render pdf cover for {}: {}", pdfPath, t.toString());
            return Optional.empty();
        }
    }
}
