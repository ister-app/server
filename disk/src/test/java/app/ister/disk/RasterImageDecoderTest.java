package app.ister.disk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RasterImageDecoderTest {

    /** The four quadrant colours the checked-in CMYK fixtures were generated from. */
    private static final int[][] QUADRANTS = {{200, 60, 40}, {40, 180, 90}, {70, 90, 210}, {180, 180, 60}};

    @Test
    void readUsesTheOrdinaryDecoderWhenNothingIsWrongWithTheFile(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("plain.jpg").toFile();
        ImageIO.write(gradient(), "jpg", file);

        assertPixelsMatch(ImageIO.read(file), RasterImageDecoder.read(file), 0);
    }

    /**
     * The case this class exists for: a three-band JPEG tagged with a profile that declares a
     * different number of components. TMDB serves a steady trickle of them (a Photoshop export with
     * a CMYK profile over RGB data) and {@code ImageIO.read} refuses every one, which used to leave
     * the artwork without a blur-hash. The fixture is built here rather than checked in because a
     * real CMYK profile runs to hundreds of kilobytes -- the JDK's grayscale profile disagrees with
     * three bands just as well, and provokes the identical exception.
     */
    @Test
    void readFallsBackToTheRasterWhenTheProfileDisagreesWithTheBandCount(@TempDir Path tempDir) throws IOException {
        byte[] jpeg = encode(gradient());
        // Same entropy-coded data in both files, so the untagged one is an exact reference: any
        // difference is the fallback's colour conversion, not the jpeg's own loss.
        File plain = Files.write(tempDir.resolve("plain.jpg"), jpeg).toFile();
        File tagged = Files.write(tempDir.resolve("mismatched-profile.jpg"),
                withIccProfile(jpeg, ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData())).toFile();
        assertThrows(IIOException.class, () -> ImageIO.read(tagged), "fixture no longer provokes the failure");

        assertPixelsMatch(ImageIO.read(plain), RasterImageDecoder.read(tagged), 1);
    }

    /**
     * {@code readRaster} performs no colour conversion whatsoever, so a plain JFIF JPEG arrives as
     * YCbCr and has to be converted by hand -- reading the bands as RGB would tint the whole image.
     */
    @Test
    void rasterFallbackConvertsYcbcrToRgb(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("plain.jpg").toFile();
        ImageIO.write(gradient(), "jpg", file);

        assertPixelsMatch(ImageIO.read(file), RasterImageDecoder.readRaster(file), 1);
    }

    /**
     * A single band is the luminance as stored, taken at face value. Note that this deliberately
     * does not match {@code ImageIO.read}, which hands back a {@code TYPE_BYTE_GRAY} image whose
     * {@code getRGB} treats the samples as linear and brightens them on the way to sRGB.
     */
    @Test
    void rasterFallbackReadsGrayscaleAtFaceValue(@TempDir Path tempDir) throws IOException {
        BufferedImage source = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_GRAY);
        source.getRaster().setSample(8, 8, 0, 90);
        File file = tempDir.resolve("gray.jpg").toFile();
        ImageIO.write(source, "jpg", file);
        int stored = ImageIO.read(file).getRaster().getSample(8, 8, 0);

        int pixel = RasterImageDecoder.readRaster(file).getRGB(8, 8);

        assertEquals(stored, (pixel >> 16) & 0xFF, "the stored luminance, not a re-lit version of it");
        assertEquals((pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, "grey must stay grey");
        assertEquals((pixel >> 8) & 0xFF, pixel & 0xFF, "grey must stay grey");
    }

    /** Four bands, stored inverted, straight CMYK (Adobe transform 0). */
    @Test
    void rasterFallbackConvertsAdobeCmyk(@TempDir Path tempDir) throws IOException {
        assertQuadrants(RasterImageDecoder.readRaster(fixture("adobe-cmyk.jpg", tempDir)));
    }

    /** Four bands that went through the extra YCC transform on the way in (Adobe transform 2). */
    @Test
    void rasterFallbackConvertsAdobeYcck(@TempDir Path tempDir) throws IOException {
        assertQuadrants(RasterImageDecoder.readRaster(fixture("adobe-ycck.jpg", tempDir)));
    }

    @Test
    void readThrowsWhenTheFileIsNotAnImageAtAll(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("not-an-image.jpg").toFile();
        Files.writeString(file.toPath(), "this is not a jpeg");

        assertThrows(IOException.class, () -> RasterImageDecoder.read(file));
    }

    private static BufferedImage gradient() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                image.setRGB(x, y, (8 * x << 16) | (8 * y << 8) | (255 - 4 * x - 4 * y));
            }
        }
        return image;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", encoded);
        return encoded.toByteArray();
    }

    /** {@code jpeg} with {@code profile} spliced in as an APP2 ICC_PROFILE segment. */
    private static byte[] withIccProfile(byte[] jpeg, byte[] profile) throws IOException {
        byte[] tag = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);
        int length = 2 + tag.length + 2 + profile.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI, the segment has to follow it
        out.write(0xFF);
        out.write(0xE2); // APP2
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(tag);
        out.write(1); // chunk 1
        out.write(1); // of 1
        out.write(profile);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static File fixture(String name, Path tempDir) throws IOException {
        Path target = tempDir.resolve(name);
        try (InputStream in = RasterImageDecoderTest.class.getResourceAsStream("/images/" + name)) {
            assertNotNull(in, name + " is missing from the test resources");
            Files.copy(in, target);
        }
        return target.toFile();
    }

    private static void assertQuadrants(BufferedImage decoded) {
        int[][] centres = {{8, 8}, {24, 8}, {8, 24}, {24, 24}};
        for (int i = 0; i < centres.length; i++) {
            int pixel = decoded.getRGB(centres[i][0], centres[i][1]);
            assertEquals(QUADRANTS[i][0], (pixel >> 16) & 0xFF, 3, "red of quadrant " + i);
            assertEquals(QUADRANTS[i][1], (pixel >> 8) & 0xFF, 3, "green of quadrant " + i);
            assertEquals(QUADRANTS[i][2], pixel & 0xFF, 3, "blue of quadrant " + i);
        }
    }

    private static void assertPixelsMatch(BufferedImage expected, BufferedImage actual, int tolerance) {
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int a = expected.getRGB(x, y);
                int b = actual.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    int difference = Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF));
                    assertTrue(difference <= tolerance,
                            "pixel (" + x + "," + y + ") differs by " + difference);
                }
            }
        }
    }
}
