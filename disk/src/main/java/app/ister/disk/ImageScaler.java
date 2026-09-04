package app.ister.disk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
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

    /** Artwork is small enough that ImageIO's default 0.75 is a false economy. */
    private static final float JPEG_QUALITY = 0.85f;

    /** Scaled bytes plus the type they were encoded as — never the source's type. */
    public record ScaledImage(byte[] bytes, MediaType contentType) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ScaledImage(byte[] otherBytes, MediaType otherType)
                    && Arrays.equals(bytes, otherBytes) && Objects.equals(contentType, otherType);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(bytes) + Objects.hashCode(contentType);
        }

        @Override
        public String toString() {
            // The bytes themselves are useless in a log line; their length is not.
            return "ScaledImage[bytes=" + (bytes == null ? 0 : bytes.length) + " bytes, contentType="
                    + contentType + "]";
        }
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

            // Halve until one more step would overshoot, then land on the target. Java2D's
            // filters sample a 2x2 (bilinear) or 4x4 (bicubic) neighbourhood, so scaling
            // 3840px straight down to 320 reads one pixel in every 144 and throws the rest
            // away — point sampling with an interpolation hint on it. Artwork came out
            // visibly aliased, and measurably so: 25.4 dB PSNR against a Lanczos reference,
            // worse than a plain bilinear resampler. Halving first puts every source pixel
            // into the average and lifts that to 33.7 dB, and the result compresses *smaller*
            // because the alias noise it removes was costing bits.
            BufferedImage current = decoded;
            while (current.getWidth() / 2 > targetWidth) {
                current = drawScaled(current, current.getWidth() / 2,
                        Math.max(1, current.getHeight() / 2), keepAlpha,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }
            BufferedImage scaled = drawScaled(current, targetWidth, targetHeight, keepAlpha,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (keepAlpha) {
                // png has no JNI hints in the native image, so a missing writer is a real
                // possibility here — write() returning false is the degrade path.
                if (!ImageIO.write(scaled, "png", out)) {
                    log.warn("No png writer while scaling {}", label);
                    return Optional.empty();
                }
                return Optional.of(new ScaledImage(out.toByteArray(), MediaType.IMAGE_PNG));
            }
            if (!writeJpeg(scaled, out, label)) {
                return Optional.empty();
            }
            return Optional.of(new ScaledImage(out.toByteArray(), MediaType.IMAGE_JPEG));
        } catch (Throwable t) {
            // Throwable on purpose: a native image without AWT throws LinkageError/
            // ExceptionInInitializerError, and a broken image must degrade to the original bytes.
            log.warn("Could not downscale {}: {}", label, t.toString());
            return Optional.empty();
        }
    }

    private static BufferedImage drawScaled(BufferedImage source, int width, int height,
                                            boolean keepAlpha, Object interpolation) {
        BufferedImage target = new BufferedImage(width, height,
                keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (keepAlpha) {
                graphics.drawImage(source, 0, 0, width, height, null);
            } else {
                graphics.drawImage(source, 0, 0, width, height, Color.WHITE, null);
            }
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /**
     * Jpeg at an explicit quality: ImageIO's default is 0.75, which is stingy for artwork
     * that is already small. Falls back to the plain writer when no parameterised one is
     * available (a real possibility in the native image), and only reports failure when
     * there is no jpeg writer at all.
     */
    private static boolean writeJpeg(BufferedImage image, ByteArrayOutputStream out, String label)
            throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (writers.hasNext()) {
            ImageWriter writer = writers.next();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
                ImageWriteParam params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, null), params);
                return true;
            } finally {
                writer.dispose();
            }
        }
        if (!ImageIO.write(image, "jpg", out)) {
            log.warn("No jpeg writer while scaling {}", label);
            return false;
        }
        return true;
    }
}
