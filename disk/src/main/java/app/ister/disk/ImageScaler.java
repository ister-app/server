package app.ister.disk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Optional;

/**
 * Downscales an image to a target width, shared by the comic page endpoint and the artwork
 * download endpoint.
 * <p>
 * Every failure mode degrades to {@link Optional#empty()} rather than throwing: the callers all
 * have original bytes to fall back on, and a missing AWT in the GraalVM native image must not
 * turn a picture into a 500.
 */
@Slf4j
@Component
public class ImageScaler {

    /** How the scaler treats a source image that carries an alpha channel. */
    public enum Alpha {
        /**
         * Composite onto white and encode jpeg. Right for comic pages, which are opaque scans
         * that only ever gain alpha from the odd png export.
         */
        FLATTEN,
        /**
         * Keep the alpha channel and encode png. Artwork can be genuinely transparent (logos,
         * clear art), and flattening it onto white ruins it on a dark surface.
         */
        PRESERVE
    }

    /** Scaled bytes plus the type they were encoded as — never the source's type. */
    public record ScaledImage(byte[] bytes, MediaType contentType) {
    }

    /**
     * {@code source} downscaled to {@code targetWidth}, or empty when the source is already that
     * narrow, cannot be decoded (an animated gif or webp reads as null), or no writer for the
     * chosen format is registered. The caller then serves the original bytes.
     *
     * @param label only used in the warn log when scaling fails
     */
    @SuppressWarnings("java:S1181") // a native image without AWT throws LinkageError, which must degrade, not propagate
    public Optional<ScaledImage> scale(InputStream source, int targetWidth, Alpha alpha, String label) {
        try {
            BufferedImage decoded = ImageIO.read(source);
            if (decoded == null || decoded.getWidth() <= targetWidth) {
                return Optional.empty();
            }
            boolean keepAlpha = alpha == Alpha.PRESERVE && decoded.getColorModel().hasAlpha();
            int targetHeight = Math.max(1,
                    Math.round(decoded.getHeight() * (targetWidth / (float) decoded.getWidth())));
            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight,
                    keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (keepAlpha) {
                    graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
                } else {
                    graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, Color.WHITE, null);
                }
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // png has no JNI hints in the native image, so a missing writer is a real possibility
            // here — write() returning false is the degrade path, not an exception.
            if (!ImageIO.write(scaled, keepAlpha ? "png" : "jpg", out)) {
                log.warn("No image writer for {} while scaling {}", keepAlpha ? "png" : "jpg", label);
                return Optional.empty();
            }
            return Optional.of(new ScaledImage(out.toByteArray(),
                    keepAlpha ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG));
        } catch (Throwable t) {
            // Throwable on purpose: a native image without AWT throws LinkageError/
            // ExceptionInInitializerError, and a broken image must degrade to the original bytes.
            log.warn("Could not downscale {}: {}", label, t.toString());
            return Optional.empty();
        }
    }
}
