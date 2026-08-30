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
 * PDF comic volumes via Apache PDFBox. The page count needs no AWT and is safe everywhere; page
 * rendering does need AWT/ImageIO, which the GraalVM native image only supports with extra
 * setup — so rendering is failure-tolerant: any error (including LinkageError from a native
 * image without AWT) degrades to "no image" instead of poisoning the scan or the request, the
 * same pattern the blur-hash computation uses.
 */
@Component
@Slf4j
public class PdfParser {

    /** Matches what the previous 100-DPI cover render produced on a letter-sized page. */
    private static final int COVER_WIDTH = 850;

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
        return renderPageJpeg(pdfPath, 0, COVER_WIDTH);
    }

    /**
     * Page {@code index} (zero-based) rendered as jpg bytes at {@code targetWidth} pixels wide,
     * or empty when the index is out of range or rendering is unavailable or fails.
     */
    @SuppressWarnings("java:S1181") // a native image without AWT throws LinkageError, which must degrade, not dead-letter the scan
    public Optional<byte[]> renderPageJpeg(Path pdfPath, int index, int targetWidth) {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            if (index < 0 || index >= document.getNumberOfPages()) {
                return Optional.empty();
            }
            // The media-box width is in points; the render scale is pixels-per-point.
            float pageWidth = document.getPage(index).getMediaBox().getWidth();
            if (pageWidth <= 0) {
                return Optional.empty();
            }
            BufferedImage image = new PDFRenderer(document).renderImage(index, targetWidth / pageWidth);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpg", out)) {
                return Optional.empty();
            }
            return Optional.of(out.toByteArray());
        } catch (Throwable t) {
            // Throwable on purpose: a native image without AWT throws LinkageError/
            // ExceptionInInitializerError, and a broken PDF must never poison the scan either.
            log.warn("Could not render pdf page {} of {}: {}", index, pdfPath, t.toString());
            return Optional.empty();
        }
    }
}
