package app.ister.transcoder.bitmapsub;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a DVD VobSub pair ({@code .idx} + {@code .sub}, what
 * {@code mkvextract} writes for a {@code dvd_subtitle} track) into bitmap cues.
 *
 * <p>The {@code .idx} is text: canvas size, the 16-colour disc palette, and per
 * cue a timestamp plus byte offset into the {@code .sub}. The {@code .sub} is an
 * MPEG program stream whose private-stream-1 packets carry subpicture units
 * (SPU): a 2-bit run-length picture in two interlaced fields, followed by
 * control sequences that pick 4 of the 16 colours, their alpha, the position
 * and the start/stop delays.</p>
 *
 * <p>A cue that cannot be decoded is skipped; the rest still comes out.</p>
 */
@Slf4j
public final class VobSubParser {

    private static final Pattern SIZE = Pattern.compile("^size:\\s*(\\d+)x(\\d+)");
    private static final Pattern TIMESTAMP =
            Pattern.compile("^timestamp:\\s*(\\d+):(\\d+):(\\d+):(\\d+),\\s*filepos:\\s*([0-9a-fA-F]+)");
    /** A cue without a stop command ends when the next one starts, but not later than this. */
    private static final long MAX_OPEN_CUE_MS = 10_000;

    private VobSubParser() {
    }

    public static BitmapSubtitle parse(Path idx, Path sub) throws IOException {
        return parse(Files.readAllLines(idx, StandardCharsets.ISO_8859_1), Files.readAllBytes(sub));
    }

    static BitmapSubtitle parse(List<String> idxLines, byte[] sub) {
        Index index = Index.read(idxLines);
        List<BitmapCue> cues = new ArrayList<>(index.entries.size());
        for (Entry entry : index.entries) {
            try {
                byte[] spu = readSpu(sub, entry.filePos());
                BitmapCue cue = spu.length == 0 ? null : decode(spu, entry.timestampMs(), index.palette);
                if (cue != null) {
                    cues.add(cue);
                }
            } catch (RuntimeException e) {
                log.debug("Skipping undecodable VobSub cue at {} ms: {}", entry.timestampMs(), e.toString());
            }
        }
        return new BitmapSubtitle(index.width, index.height, closeOpenCues(cues));
    }

    /** One {@code timestamp: …, filepos: …} line of the index. */
    private record Entry(long timestampMs, int filePos) {
    }

    /** What the {@code .idx} says: canvas, disc palette (empty when absent) and the cue entries. */
    private static final class Index {
        int width = 720;
        int height = 576;
        int[] palette = new int[0];
        final List<Entry> entries = new ArrayList<>();

        static Index read(List<String> lines) {
            Index index = new Index();
            for (String raw : lines) {
                index.readLine(raw.strip());
            }
            return index;
        }

        private void readLine(String line) {
            Matcher timestamp = TIMESTAMP.matcher(line);
            Matcher size = SIZE.matcher(line);
            if (timestamp.find()) {
                long ms = ((Long.parseLong(timestamp.group(1)) * 60 + Long.parseLong(timestamp.group(2))) * 60
                        + Long.parseLong(timestamp.group(3))) * 1000 + Long.parseLong(timestamp.group(4));
                entries.add(new Entry(ms, (int) Long.parseLong(timestamp.group(5), 16)));
            } else if (size.find()) {
                width = Integer.parseInt(size.group(1));
                height = Integer.parseInt(size.group(2));
            } else if (line.startsWith("palette:")) {
                palette = parsePalette(line.substring("palette:".length()));
            }
        }
    }

    private static int[] parsePalette(String value) {
        String[] parts = value.split(",");
        if (parts.length < 16) {
            return new int[0];
        }
        int[] palette = new int[16];
        try {
            for (int i = 0; i < 16; i++) {
                palette[i] = Integer.parseInt(parts[i].strip(), 16) & 0xFFFFFF;
            }
        } catch (NumberFormatException _) {
            return new int[0];
        }
        return palette;
    }

    private static List<BitmapCue> closeOpenCues(List<BitmapCue> cues) {
        List<BitmapCue> closed = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i++) {
            BitmapCue cue = cues.get(i);
            long next = i + 1 < cues.size() ? cues.get(i + 1).startMs() : Long.MAX_VALUE;
            long end = cue.endMs() > cue.startMs() ? cue.endMs() : cue.startMs() + MAX_OPEN_CUE_MS;
            long latest = Math.max(next, cue.startMs() + 1);
            closed.add(cue.withEnd(Math.min(end, latest)));
        }
        return closed;
    }

    /**
     * Collects one SPU starting at a pack header. An SPU larger than one PES
     * packet continues in the packs that follow; its first two bytes say how
     * long it is in total. Empty when the stream ran out before it was complete.
     */
    private static byte[] readSpu(byte[] buf, int start) {
        SpuAssembler spu = new SpuAssembler();
        int pos = start;
        while (pos >= 0 && !spu.complete()) {
            pos = spu.consume(buf, pos);
        }
        return spu.complete() ? spu.data : new byte[0];
    }

    private static final class SpuAssembler {
        byte[] data;
        int filled;

        boolean complete() {
            return data != null && filled >= data.length;
        }

        /** Handles the pack header or PES packet at {@code pos}; returns the next position, or -1 to stop. */
        int consume(byte[] buf, int pos) {
            if (pos + 6 > buf.length || startCode(buf, pos) < 0) {
                return -1;
            }
            int code = startCode(buf, pos);
            if (code == 0xBA) { // pack header: 14 bytes + stuffing
                return pos + 14 <= buf.length ? pos + 14 + (buf[pos + 13] & 0x07) : -1;
            }
            int next = pos + 6 + u16(buf, pos + 4);
            boolean subpicture = code == 0xBD && next <= buf.length; // private stream 1
            // payload starts after the PES header data and the substream id
            if (subpicture && !append(buf, pos + 9 + (buf[pos + 8] & 0xFF) + 1, next)) {
                return -1;
            }
            return next;
        }

        private boolean append(byte[] buf, int payload, int end) {
            if (data == null) {
                int total = u16(buf, payload);
                if (total < 4) {
                    return false;
                }
                data = new byte[total];
            }
            int n = Math.min(end - payload, data.length - filled);
            System.arraycopy(buf, payload, data, filled, n);
            filled += n;
            return true;
        }
    }

    /** The stream id of the {@code 00 00 01 xx} start code at {@code pos}, or -1. */
    private static int startCode(byte[] buf, int pos) {
        return buf[pos] == 0 && buf[pos + 1] == 0 && buf[pos + 2] == 1 ? buf[pos + 3] & 0xFF : -1;
    }

    private static int u16(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
    }

    private static BitmapCue decode(byte[] spu, long timestampMs, int[] discPalette) {
        SpuControl control = SpuControl.read(spu);
        int w = control.x2 - control.x1 + 1;
        int h = control.y2 - control.y1 + 1;
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
            return null;
        }
        int[] lut = lookupTable(control.colors, control.alpha, discPalette);
        int[] argb = new int[w * h];
        decodeField(spu, control.evenOffset, 0, w, h, lut, argb);
        decodeField(spu, control.oddOffset, 1, w, h, lut, argb);

        long start = timestampMs + delayMs(Math.max(control.startDelay, 0));
        long stop = control.stopDelay > 0 ? timestampMs + delayMs(control.stopDelay) : start;
        return BitmapCue.cropped(start, stop, control.x1, control.y1, w, h, control.forced, argb);
    }

    /** What an SPU's control sequences say: colours, alpha, position, field offsets and delays. */
    private static final class SpuControl {
        int[] colors = {0, 1, 2, 3};
        int[] alpha = {0, 15, 15, 15};
        int x1;
        int x2;
        int y1;
        int y2;
        int evenOffset = 4;
        int oddOffset = 4;
        int startDelay = -1;
        int stopDelay = -1;
        boolean forced;

        /** Walks the chain of control sequences; the last one points at itself. */
        static SpuControl read(byte[] spu) {
            SpuControl control = new SpuControl();
            int sequence = u16(spu, 2);
            boolean last = false;
            while (!last) {
                int delay = u16(spu, sequence);
                int next = u16(spu, sequence + 2);
                int p = sequence + 4;
                while (p >= 0 && p < spu.length) {
                    p = control.apply(spu, p, delay);
                }
                last = next == sequence || next + 4 > spu.length;
                sequence = next;
            }
            return control;
        }

        /** Applies the command at {@code p}; returns the position after it, or -1 at the end of the sequence. */
        private int apply(byte[] spu, int p, int delay) {
            int args = p + 1;
            switch (spu[p] & 0xFF) {
                case 0x00 -> {
                    forced = true;
                    startDelay = delay;
                    return args;
                }
                case 0x01 -> {
                    startDelay = delay;
                    return args;
                }
                case 0x02 -> {
                    stopDelay = delay;
                    return args;
                }
                case 0x03 -> {
                    colors = nibbles(spu, args);
                    return args + 2;
                }
                case 0x04 -> {
                    alpha = nibbles(spu, args);
                    return args + 2;
                }
                case 0x05 -> {
                    x1 = ((spu[args] & 0xFF) << 4) | ((spu[args + 1] & 0xFF) >> 4);
                    x2 = ((spu[args + 1] & 0x0F) << 8) | (spu[args + 2] & 0xFF);
                    y1 = ((spu[args + 3] & 0xFF) << 4) | ((spu[args + 4] & 0xFF) >> 4);
                    y2 = ((spu[args + 4] & 0x0F) << 8) | (spu[args + 5] & 0xFF);
                    return args + 6;
                }
                case 0x06 -> {
                    evenOffset = u16(spu, args);
                    oddOffset = u16(spu, args + 2);
                    return args + 4;
                }
                default -> {
                    return -1; // 0xFF, or something we don't know the length of
                }
            }
        }
    }

    /** Control-sequence delays count in units of 1024/90000 s. */
    private static long delayMs(int delay) {
        return delay * 1024L / 90;
    }

    /** Four 4-bit values packed high-to-low; index 0 is the background. */
    private static int[] nibbles(byte[] spu, int p) {
        return new int[]{spu[p + 1] & 0x0F, (spu[p + 1] & 0xFF) >> 4, spu[p] & 0x0F, (spu[p] & 0xFF) >> 4};
    }

    /**
     * The 4 ARGB colours of this picture. Without a disc palette (a raw VOB
     * has it in the IFO, not in the stream) the visible colours become a
     * white-to-black ramp in order of appearance, which is what ffmpeg does too.
     */
    private static int[] lookupTable(int[] colors, int[] alpha, int[] discPalette) {
        int[] lut = new int[4];
        int visible = 0;
        for (int a : alpha) {
            if (a != 0) {
                visible++;
            }
        }
        int level = 0xFF;
        int step = visible > 1 ? 0xFF / (visible - 1) : 0;
        for (int i = 0; i < 4; i++) {
            if (alpha[i] == 0) {
                continue;
            }
            int rgb;
            if (discPalette.length == 16) {
                rgb = discPalette[colors[i]];
            } else {
                rgb = level * 0x010101;
                level = Math.max(0, level - step);
            }
            lut[i] = ((alpha[i] * 17) << 24) | rgb;
        }
        return lut;
    }

    /**
     * One interlaced field. Runs are nibble-coded: 1, 2, 3 or 4 nibbles hold
     * {@code length << 2 | colour}; a length of 0 means "to the end of the
     * line". Every line starts byte-aligned.
     */
    private static void decodeField(byte[] spu, int offset, int firstRow, int w, int h, int[] lut, int[] out) {
        NibbleReader in = new NibbleReader(spu, offset);
        for (int y = firstRow; y < h; y += 2) {
            int x = 0;
            while (x < w && in.hasNext()) {
                int v = in.nextRun();
                int run = v >> 2;
                if (run == 0 || run > w - x) {
                    run = w - x;
                }
                int pixel = lut[v & 3];
                if (pixel != 0) {
                    Arrays.fill(out, y * w + x, y * w + x + run, pixel);
                }
                x += run;
            }
            in.alignToByte();
        }
    }

    /** Nibble cursor over the picture data; reads past the end yield 0. */
    private static final class NibbleReader {
        private final byte[] data;
        private int nibble;

        NibbleReader(byte[] data, int byteOffset) {
            this.data = data;
            this.nibble = byteOffset * 2;
        }

        boolean hasNext() {
            return nibble < data.length * 2;
        }

        void alignToByte() {
            nibble += nibble & 1;
        }

        /** The next run value: it grows by a nibble while it is too small to hold a length. */
        int nextRun() {
            int v = next();
            for (int limit = 0x4; limit <= 0x40 && v < limit; limit <<= 2) {
                v = (v << 4) | next();
            }
            return v;
        }

        private int next() {
            int value = nibbleAt(data, nibble);
            nibble++;
            return value;
        }
    }

    private static int nibbleAt(byte[] spu, int nibble) {
        int index = nibble >> 1;
        if (index >= spu.length) {
            return 0;
        }
        return (nibble & 1) == 0 ? (spu[index] & 0xFF) >> 4 : spu[index] & 0x0F;
    }
}
