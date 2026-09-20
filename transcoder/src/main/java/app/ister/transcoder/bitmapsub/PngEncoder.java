package app.ister.transcoder.bitmapsub;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Minimal 8-bit RGBA PNG writer. Deliberately not ImageIO: the PNG writer has
 * no JNI hints in the native image, and nothing here needs AWT — a PNG is a
 * zlib stream between a handful of CRC'd chunks.
 */
final class PngEncoder {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private PngEncoder() {
    }

    /** Encodes {@code w * h} non-premultiplied ARGB pixels (row-major). */
    static byte[] encodeRgba(int w, int h, int[] argb) {
        if (w <= 0 || h <= 0 || argb.length < (long) w * h) {
            throw new IllegalArgumentException("Bad PNG dimensions " + w + "x" + h + " for " + argb.length + " pixels");
        }
        byte[] raw = new byte[h * (1 + w * 4)];
        int o = 0;
        for (int y = 0; y < h; y++) {
            raw[o++] = 0; // filter type "none"
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                raw[o++] = (byte) (p >> 16);
                raw[o++] = (byte) (p >> 8);
                raw[o++] = (byte) p;
                raw[o++] = (byte) (p >>> 24);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 8 + 64);
        out.writeBytes(SIGNATURE);
        ByteBuffer header = ByteBuffer.allocate(13);
        header.putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        writeChunk(out, "IHDR", header.array(), 13);

        try (Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION)) {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(raw.length / 8 + 64);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                compressed.write(buffer, 0, deflater.deflate(buffer));
            }
            writeChunk(out, "IDAT", compressed.toByteArray(), compressed.size());
        }
        writeChunk(out, "IEND", new byte[0], 0);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data, int length) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data, 0, length);
        out.writeBytes(ByteBuffer.allocate(4).putInt(length).array());
        out.writeBytes(typeBytes);
        out.write(data, 0, length);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
