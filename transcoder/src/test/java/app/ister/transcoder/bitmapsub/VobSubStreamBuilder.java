package app.ister.transcoder.bitmapsub;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a synthetic VobSub {@code .idx}/{@code .sub} pair for tests. Every cue
 * is a {@code w}x{@code h} picture area holding a solid block of colour 1 with a
 * transparent margin around it.
 */
final class VobSubStreamBuilder {

    private final ByteArrayOutputStream sub = new ByteArrayOutputStream();
    private final List<String> idx = new ArrayList<>();

    VobSubStreamBuilder(String size, String palette) {
        idx.add("# VobSub index file, v7 (do not modify this line!)");
        if (size != null) {
            idx.add("size: " + size);
        }
        if (palette != null) {
            idx.add("palette: " + palette);
        }
        idx.add("id: en, index: 0");
    }

    /**
     * @param margin     transparent pixels on every side of the block (at least 16)
     * @param stopDelay  control-sequence delay of the stop command, 0 for "no stop command"
     * @param packets    number of PES packets to spread the SPU over
     */
    VobSubStreamBuilder cue(long timestampMs, int x, int y, int w, int h, int margin, int stopDelay, int packets) {
        idx.add(String.format(Locale.ROOT, "timestamp: %02d:%02d:%02d:%03d, filepos: %09x",
                timestampMs / 3_600_000, timestampMs / 60_000 % 60, timestampMs / 1000 % 60, timestampMs % 1000,
                sub.size()));
        byte[] spu = spu(x, y, w, h, margin, stopDelay);
        int chunk = (spu.length + packets - 1) / packets;
        for (int from = 0; from < spu.length; from += chunk) {
            pack(spu, from, Math.min(spu.length, from + chunk));
        }
        return this;
    }

    List<String> idxLines() {
        return idx;
    }

    byte[] subBytes() {
        return sub.toByteArray();
    }

    private void pack(byte[] spu, int from, int to) {
        sub.writeBytes(new byte[]{0, 0, 1, (byte) 0xBA, 0x44, 0, 4, 0, 4, 1, 1, (byte) 0x89, (byte) 0xC3, (byte) 0xF8});
        int length = 3 + 5 + 1 + (to - from);
        sub.writeBytes(new byte[]{0, 0, 1, (byte) 0xBD, (byte) (length >> 8), (byte) length,
                (byte) 0x81, (byte) 0x80, 5, 0x21, 0, 1, 0, 1, 0x20});
        sub.write(spu, from, to - from);
    }

    private static byte[] spu(int x, int y, int w, int h, int margin, int stopDelay) {
        byte[] even = field(w, h, margin, 0);
        byte[] odd = field(w, h, margin, 1);
        int evenOffset = 4;
        int oddOffset = evenOffset + even.length;
        int control = oddOffset + odd.length;
        int second = control + 4 + 3 + 3 + 7 + 5 + 1 + 1;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int total = stopDelay > 0 ? second + 6 : second;
        u16(out, total);
        u16(out, control);
        out.writeBytes(even);
        out.writeBytes(odd);

        u16(out, 0); // delay
        u16(out, stopDelay > 0 ? second : control);
        out.writeBytes(new byte[]{0x03, 0x00, 0x30}); // colour 1 -> disc palette entry 3, background entry 0
        out.writeBytes(new byte[]{0x04, 0x00, (byte) 0xF0}); // colour 1 opaque, the rest transparent
        int x2 = x + w - 1;
        int y2 = y + h - 1;
        out.writeBytes(new byte[]{0x05, (byte) (x >> 4), (byte) ((x & 0xF) << 4 | x2 >> 8), (byte) x2,
                (byte) (y >> 4), (byte) ((y & 0xF) << 4 | y2 >> 8), (byte) y2});
        out.write(0x06);
        u16(out, evenOffset);
        u16(out, oddOffset);
        out.write(0x01);
        out.write(0xFF);
        if (stopDelay > 0) {
            u16(out, stopDelay);
            u16(out, second);
            out.write(0x02);
            out.write(0xFF);
        }
        return out.toByteArray();
    }

    private static byte[] field(int w, int h, int margin, int firstRow) {
        NibbleWriter nibbles = new NibbleWriter();
        for (int row = firstRow; row < h; row += 2) {
            if (row >= margin && row < h - margin) {
                nibbles.run(margin, 0);
                nibbles.run(w - 2 * margin, 1);
            }
            nibbles.toEndOfLine(0);
            nibbles.align();
        }
        return nibbles.bytes();
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value);
    }

    private static final class NibbleWriter {
        private final List<Integer> nibbles = new ArrayList<>();

        /** 3-nibble run for lengths 16..63, 4 nibbles for 64..255. */
        void run(int length, int color) {
            if (length < 16 || length > 255) {
                throw new IllegalArgumentException("test builder only encodes runs of 16..255, got " + length);
            }
            int v = length << 2 | color;
            if (length > 63) {
                nibbles.add(v >> 12);
            }
            nibbles.add(v >> 8 & 0xF);
            nibbles.add(v >> 4 & 0xF);
            nibbles.add(v & 0xF);
        }

        void toEndOfLine(int color) {
            nibbles.add(0);
            nibbles.add(0);
            nibbles.add(0);
            nibbles.add(color);
        }

        void align() {
            if (nibbles.size() % 2 != 0) {
                nibbles.add(0);
            }
        }

        byte[] bytes() {
            byte[] out = new byte[nibbles.size() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (nibbles.get(2 * i) << 4 | nibbles.get(2 * i + 1));
            }
            return out;
        }
    }
}
