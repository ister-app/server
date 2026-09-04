package app.ister.disk;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ImageScalerTest {

    private final ImageScaler scaler = new ImageScaler();

    private static InputStream jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new ByteArrayInputStream(out.toByteArray());
    }

    /** Lossless, so the test pattern reaches the scaler intact — a jpeg round trip
     *  turns a 1px checkerboard into grey mush on its own and would prove nothing. */
    private static InputStream png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new ByteArrayInputStream(out.toByteArray());
    }

    /**
     * Uniform random noise, fixed seed. Deliberately not a checkerboard: every 2x2 window
     * of one holds two black and two white pixels, so even a 2x2 sample averages to grey
     * and the pattern cannot tell the two algorithms apart. Noise can: averaging N source
     * pixels divides the standard deviation by sqrt(N), so how flat the result comes out
     * says exactly how many source pixels reached it.
     */
    private static BufferedImage noise(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(42);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                int v = random.nextInt(256);
                image.setRGB(x, y, 0xFF000000 | (v << 16) | (v << 8) | v);
            }
        }
        return image;
    }

    private static BufferedImage read(ImageScaler.ScaledImage scaled) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(scaled.bytes()));
    }

    /**
     * The regression this exists for. Scaling 16x down in one Java2D step averages a 2x2
     * neighbourhood and ignores the other 252 source pixels behind each output pixel —
     * point sampling with an interpolation hint on it, which left artwork visibly aliased.
     * Averaging all 256 has to divide the noise's spread by ~16, not by ~2.
     */
    @Test
    void everySourcePixelReachesTheAverage() throws IOException {
        Optional<ImageScaler.ScaledImage> scaled =
                scaler.scale(png(noise(1024)), 64, ImageScaler.Alpha.FLATTEN, "noise");

        assertTrue(scaled.isPresent());
        BufferedImage image = read(scaled.get());
        assertEquals(64, image.getWidth());

        double sum = 0;
        double sumSquares = 0;
        int n = 0;
        // Skip the border, where jpeg ringing of an extreme pattern lives.
        for (int x = 4; x < image.getWidth() - 4; x++) {
            for (int y = 4; y < image.getHeight() - 4; y++) {
                int luma = image.getRGB(x, y) & 0xFF;
                sum += luma;
                sumSquares += (double) luma * luma;
                n++;
            }
        }
        double stdDev = Math.sqrt(sumSquares / n - (sum / n) * (sum / n));
        // Source spread is ~74 (uniform 0..255). A 2x2 average leaves ~37, all 256 leave ~5.
        assertTrue(stdDev < 15,
                "spread of " + Math.round(stdDev) + " left after a 16x downscale — the scaler "
                        + "is sampling a handful of pixels instead of averaging them all");
    }

    @Test
    void scalesToTheTargetWidthKeepingTheAspectRatio() throws IOException {
        BufferedImage source = new BufferedImage(1000, 500, BufferedImage.TYPE_INT_RGB);
        Optional<ImageScaler.ScaledImage> scaled =
                scaler.scale(jpeg(source), 320, ImageScaler.Alpha.FLATTEN, "wide");

        assertTrue(scaled.isPresent());
        BufferedImage image = read(scaled.get());
        assertEquals(320, image.getWidth());
        assertEquals(160, image.getHeight());
        assertEquals(MediaType.IMAGE_JPEG, scaled.get().contentType());
    }

    @Test
    void preservingAlphaProducesPng() throws IOException {
        BufferedImage source = new BufferedImage(800, 800, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 800; x++) {
            for (int y = 0; y < 800; y++) {
                source.setRGB(x, y, 0x80FF0000);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        Optional<ImageScaler.ScaledImage> scaled = scaler.scale(
                new ByteArrayInputStream(png.toByteArray()), 160, ImageScaler.Alpha.PRESERVE, "logo");

        assertTrue(scaled.isPresent());
        assertEquals(MediaType.IMAGE_PNG, scaled.get().contentType());
        assertTrue(read(scaled.get()).getColorModel().hasAlpha());
    }

    @Test
    void flatteningAnAlphaSourceProducesJpeg() throws IOException {
        BufferedImage source = new BufferedImage(800, 800, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        Optional<ImageScaler.ScaledImage> scaled = scaler.scale(
                new ByteArrayInputStream(png.toByteArray()), 160, ImageScaler.Alpha.FLATTEN, "page");

        assertTrue(scaled.isPresent());
        assertEquals(MediaType.IMAGE_JPEG, scaled.get().contentType());
    }

    @Test
    void aSourceAlreadyNarrowEnoughIsLeftToTheCaller() throws IOException {
        BufferedImage source = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);

        assertTrue(scaler.scale(jpeg(source), 320, ImageScaler.Alpha.FLATTEN, "small").isEmpty());
    }

    @Test
    void anUndecodableSourceIsLeftToTheCaller() {
        InputStream garbage = new ByteArrayInputStream("not an image".getBytes());

        assertTrue(scaler.scale(garbage, 320, ImageScaler.Alpha.FLATTEN, "garbage").isEmpty());
    }
}
