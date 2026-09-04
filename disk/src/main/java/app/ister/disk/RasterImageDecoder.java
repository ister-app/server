package app.ister.disk;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;

/**
 * Decodes an image file, falling back to the raw raster when {@link ImageIO#read} refuses it.
 *
 * <p>A fair number of JPEGs in the wild carry an ICC profile whose component count disagrees with
 * the actual number of raster bands -- typically a Photoshop export tagged with a four-component
 * (CMYK) profile over three-band image data. {@code ImageIO.read} builds its {@code ColorModel}
 * from that profile and then throws {@code "Numbers of source Raster bands and source color space
 * components do not match"}, even though the pixel data itself is perfectly ordinary. TMDB serves
 * plenty of them, and every one of those used to end up as artwork without a blur-hash.
 *
 * <p>The fallback reads the raster directly, which skips colour management entirely, and does the
 * colour conversion by hand. That conversion is the reason this cannot simply hand the raster to a
 * {@code BufferedImage}: {@code readRaster} performs no conversion at all, so a plain JFIF JPEG
 * comes back as YCbCr, not RGB. What the bands mean is decided by the Adobe APP14 marker
 * ({@link #adobeTransform}) when present, and by the band count otherwise.
 */
@Slf4j
public final class RasterImageDecoder {

    /** APP14 transform values; -1 stands for "no Adobe marker in this file". */
    private static final int TRANSFORM_NONE = 0;
    private static final int TRANSFORM_YCCK = 2;
    private static final int TRANSFORM_ABSENT = -1;

    private RasterImageDecoder() {
    }

    /**
     * The decoded image, never null.
     *
     * @throws IOException when neither the normal read nor the raster fallback yields an image
     */
    public static BufferedImage read(File file) throws IOException {
        try {
            BufferedImage image = ImageIO.read(file);
            if (image != null) {
                return image;
            }
            log.debug("No ImageIO reader produced an image for {}, trying the raster fallback", file);
        } catch (IIOException | RuntimeException e) {
            // The mismatched-profile case arrives as an IIOException, a profile the colour
            // management module chokes on as a CMMException. Anything else reaching this point is a
            // file the raster read will refuse as well, so there is nothing to lose by trying.
            log.debug("ImageIO.read failed for {} ({}), trying the raster fallback", file, e.toString());
        }
        return readRaster(file);
    }

    /**
     * Visible for testing: whether {@link #read} needs this path at all depends on the JDK's colour
     * management, so a test that wants to exercise the conversion has to ask for it explicitly.
     */
    static BufferedImage readRaster(File file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            if (input == null) {
                throw new IOException("No image input stream for " + file);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No image reader for " + file);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                if (!reader.canReadRaster()) {
                    throw new IOException("Reader for " + file + " cannot read a raster");
                }
                return toRgb(reader.readRaster(0, null), adobeTransform(file));
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage toRgb(Raster raster, int transform) throws IOException {
        int width = raster.getWidth();
        int height = raster.getHeight();
        int bands = raster.getNumBands();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixel = new int[bands];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.getPixel(x, y, pixel);
                image.setRGB(x, y, switch (bands) {
                    case 1 -> rgb(pixel[0], pixel[0], pixel[0]);
                    case 3 -> threeBandRgb(pixel, transform);
                    case 4 -> fourBandRgb(pixel, transform);
                    default -> throw new IOException("Cannot convert a " + bands + "-band raster to RGB");
                });
            }
        }
        return image;
    }

    /**
     * Three bands are YCbCr unless the Adobe marker explicitly says the encoder skipped the colour
     * transform. A file with no Adobe marker at all is JFIF, hence YCbCr.
     */
    private static int threeBandRgb(int[] pixel, int transform) {
        if (transform == TRANSFORM_NONE) {
            return rgb(pixel[0], pixel[1], pixel[2]);
        }
        return ycbcrToRgb(pixel[0], pixel[1], pixel[2]);
    }

    /**
     * Four bands are CMYK, stored either directly or through YCCK.
     *
     * <p>Adobe writes CMYK inverted, so the stored samples are already {@code 255 - C} -- which is
     * exactly the factor a composite onto white needs, hence the deceptively short arithmetic.
     * YCCK adds a second inversion: libjpeg flips the CMY samples back before running the YCC
     * transform, so undoing that transform yields {@code C}, not {@code 255 - C}, and it has to be
     * flipped once more here. In practice a four-band JPEG without an Adobe marker does not occur;
     * treating one as Adobe anyway beats emitting a photographic negative.
     */
    private static int fourBandRgb(int[] pixel, int transform) {
        int c = pixel[0];
        int m = pixel[1];
        int y = pixel[2];
        if (transform == TRANSFORM_YCCK) {
            int ycc = ycbcrToRgb(pixel[0], pixel[1], pixel[2]);
            c = 255 - ((ycc >> 16) & 0xFF);
            m = 255 - ((ycc >> 8) & 0xFF);
            y = 255 - (ycc & 0xFF);
        }
        int k = pixel[3];
        return rgb(c * k / 255, m * k / 255, y * k / 255);
    }

    private static int ycbcrToRgb(int y, int cb, int cr) {
        return rgb(
                clamp(Math.round(y + 1.402f * (cr - 128))),
                clamp(Math.round(y - 0.344136f * (cb - 128) - 0.714136f * (cr - 128))),
                clamp(Math.round(y + 1.772f * (cb - 128))));
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return Math.clamp(value, 0, 255);
    }

    /**
     * The transform byte of the JPEG's Adobe APP14 segment, or {@link #TRANSFORM_ABSENT} when the
     * file carries no such segment (or is not a JPEG at all). Walks the marker segments rather than
     * scanning for the byte pattern, which would also hit it inside entropy-coded data.
     */
    @SuppressWarnings("java:S3776") // a marker walk is a loop of small cases; splitting it hides the structure
    private static int adobeTransform(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            DataInputStream data = new DataInputStream(in);
            if (data.readUnsignedShort() != 0xFFD8) {
                return TRANSFORM_ABSENT;
            }
            while (true) {
                int marker = data.readUnsignedByte();
                while (marker != 0xFF) {
                    marker = data.readUnsignedByte();
                }
                while (marker == 0xFF) {
                    marker = data.readUnsignedByte();
                }
                if (marker == 0xD9 || marker == 0xDA) {
                    return TRANSFORM_ABSENT; // end of image, or start of scan: no APP14 will follow
                }
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                    continue; // standalone marker, no length field
                }
                int length = data.readUnsignedShort() - 2;
                if (marker == 0xEE && length >= 12) {
                    byte[] segment = new byte[length];
                    data.readFully(segment);
                    if (new String(segment, 0, 5, StandardCharsets.US_ASCII).equals("Adobe")) {
                        return segment[11] & 0xFF;
                    }
                } else {
                    data.skipNBytes(length);
                }
            }
        } catch (IOException e) {
            log.debug("Could not read the Adobe marker of {}: {}", file, e.getMessage());
            return TRANSFORM_ABSENT;
        }
    }
}
