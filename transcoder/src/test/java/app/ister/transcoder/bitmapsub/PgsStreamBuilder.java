package app.ister.transcoder.bitmapsub;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Builds a synthetic PGS {@code .sup} byte stream for tests: solid rectangles
 * instead of pictures from a real disc.
 */
final class PgsStreamBuilder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int width;
    private final int height;

    PgsStreamBuilder(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** A display set showing a {@code w}x{@code h} rectangle of palette entry 1 at {@code x},{@code y}. */
    PgsStreamBuilder show(long ptsMs, int x, int y, int w, int h, int yy, int cr, int cb, int alpha) {
        return show(ptsMs, x, y, w, h, yy, cr, cb, alpha, 0x80, 1);
    }

    PgsStreamBuilder show(long ptsMs, int x, int y, int w, int h, int yy, int cr, int cb, int alpha,
                          int compositionState, int odsFragments) {
        ByteBuffer pcs = ByteBuffer.allocate(19);
        pcs.putShort((short) width).putShort((short) height).put((byte) 0x10).putShort((short) 0)
                .put((byte) compositionState).put((byte) 0).put((byte) 0).put((byte) 1)
                .putShort((short) 0).put((byte) 0).put((byte) 0).putShort((short) x).putShort((short) y);
        segment(0x16, ptsMs, pcs.array());
        segment(0x14, ptsMs, new byte[]{0, 0, 1, (byte) yy, (byte) cr, (byte) cb, (byte) alpha});

        byte[] rle = rectangleRle(w, h);
        ByteBuffer first = ByteBuffer.allocate(11 + rle.length);
        int total = rle.length + 4;
        first.putShort((short) 0).put((byte) 0).put((byte) 0)
                .put((byte) (total >> 16)).putShort((short) total).putShort((short) w).putShort((short) h).put(rle);
        byte[] data = first.array();
        if (odsFragments <= 1) {
            data[3] = (byte) 0xC0; // first and last
            segment(0x15, ptsMs, data);
        } else {
            int split = 11 + rle.length / 2;
            byte[] head = java.util.Arrays.copyOfRange(data, 0, split);
            head[3] = (byte) 0x80;
            segment(0x15, ptsMs, head);
            ByteBuffer tail = ByteBuffer.allocate(4 + data.length - split);
            tail.putShort((short) 0).put((byte) 0).put((byte) 0x40).put(data, split, data.length - split);
            segment(0x15, ptsMs, tail.array());
        }
        segment(0x80, ptsMs, new byte[0]);
        return this;
    }

    /** A display set with no objects: clears the screen. */
    PgsStreamBuilder clear(long ptsMs) {
        ByteBuffer pcs = ByteBuffer.allocate(11);
        pcs.putShort((short) width).putShort((short) height).put((byte) 0x10).putShort((short) 0)
                .put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        segment(0x16, ptsMs, pcs.array());
        segment(0x80, ptsMs, new byte[0]);
        return this;
    }

    byte[] build() {
        return out.toByteArray();
    }

    private void segment(int type, long ptsMs, byte[] payload) {
        ByteBuffer header = ByteBuffer.allocate(13);
        header.put((byte) 'P').put((byte) 'G').putInt((int) (ptsMs * 90)).putInt(0)
                .put((byte) type).putShort((short) payload.length);
        out.writeBytes(header.array());
        out.writeBytes(payload);
    }

    /** Every line: one run of {@code w} pixels of colour 1, then end-of-line. */
    private static byte[] rectangleRle(int w, int h) {
        ByteArrayOutputStream rle = new ByteArrayOutputStream();
        for (int row = 0; row < h; row++) {
            rle.write(0);
            rle.write(0xC0 | (w >> 8)); // 14-bit length + colour follows
            rle.write(w & 0xFF);
            rle.write(1);
            rle.write(0);
            rle.write(0);
        }
        return rle.toByteArray();
    }
}
