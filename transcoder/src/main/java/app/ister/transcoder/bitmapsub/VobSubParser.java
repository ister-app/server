package app.ister.transcoder.bitmapsub;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        int width = 720;
        int height = 576;
        int[] palette = null;
        List<long[]> entries = new ArrayList<>(); // {timestampMs, filepos}
        for (String raw : idxLines) {
            String line = raw.strip();
            Matcher m = TIMESTAMP.matcher(line);
            if (m.find()) {
                long ms = ((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60
                        + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
                entries.add(new long[]{ms, Long.parseLong(m.group(5), 16)});
            } else if ((m = SIZE.matcher(line)).find()) {
                width = Integer.parseInt(m.group(1));
                height = Integer.parseInt(m.group(2));
            } else if (line.startsWith("palette:")) {
                palette = parsePalette(line.substring("palette:".length()));
            }
        }

        List<BitmapCue> cues = new ArrayList<>(entries.size());
        for (long[] entry : entries) {
            try {
                byte[] spu = readSpu(sub, (int) entry[1]);
                BitmapCue cue = spu == null ? null : decode(spu, entry[0], palette);
                if (cue != null) {
                    cues.add(cue);
                }
            } catch (RuntimeException e) {
                log.debug("Skipping undecodable VobSub cue at {} ms: {}", entry[0], e.toString());
            }
        }
        return new BitmapSubtitle(width, height, closeOpenCues(cues));
    }

    private static int[] parsePalette(String value) {
        String[] parts = value.split(",");
        if (parts.length < 16) {
            return null;
        }
        int[] palette = new int[16];
        try {
            for (int i = 0; i < 16; i++) {
                palette[i] = Integer.parseInt(parts[i].strip(), 16) & 0xFFFFFF;
            }
        } catch (NumberFormatException _) {
            return null;
        }
        return palette;
    }

    private static List<BitmapCue> closeOpenCues(List<BitmapCue> cues) {
        List<BitmapCue> closed = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i++) {
            BitmapCue cue = cues.get(i);
            long next = i + 1 < cues.size() ? cues.get(i + 1).startMs() : Long.MAX_VALUE;
            long end = cue.endMs() > cue.startMs() ? cue.endMs() : cue.startMs() + MAX_OPEN_CUE_MS;
            closed.add(cue.withEnd(Math.min(end, Math.max(next, cue.startMs() + 1))));
        }
        return closed;
    }

    /**
     * Collects one SPU starting at a pack header. An SPU larger than one PES
     * packet continues in the packs that follow; its first two bytes say how
     * long it is in total.
     */
    private static byte[] readSpu(byte[] buf, int pos) {
        byte[] spu = null;
        int filled = 0;
        while (pos + 4 <= buf.length && startCode(buf, pos) >= 0) {
            int code = startCode(buf, pos);
            if (code == 0xBA) { // pack header: 14 bytes + stuffing
                if (pos + 14 > buf.length) {
                    break;
                }
                pos += 14 + (buf[pos + 13] & 0x07);
                continue;
            }
            if (pos + 6 > buf.length) {
                break;
            }
            int pesLength = u16(buf, pos + 4);
            int next = pos + 6 + pesLength;
            if (code == 0xBD && next <= buf.length) { // private stream 1
                int payload = pos + 9 + (buf[pos + 8] & 0xFF) + 1; // + PES header data + substream id
                if (spu == null) {
                    int total = u16(buf, payload);
                    if (total < 4) {
                        return null;
                    }
                    spu = new byte[total];
                }
                int n = Math.min(next - payload, spu.length - filled);
                System.arraycopy(buf, payload, spu, filled, n);
                filled += n;
                if (filled >= spu.length) {
                    return spu;
                }
            }
            pos = next;
        }
        return null; // ran out of stream before the SPU was complete
    }

    /** The stream id of the {@code 00 00 01 xx} start code at {@code pos}, or -1. */
    private static int startCode(byte[] buf, int pos) {
        return buf[pos] == 0 && buf[pos + 1] == 0 && buf[pos + 2] == 1 ? buf[pos + 3] & 0xFF : -1;
    }

    private static int u16(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
    }

    private static BitmapCue decode(byte[] spu, long timestampMs, int[] discPalette) {
        int[] colors = {0, 1, 2, 3};
        int[] alpha = {0, 15, 15, 15};
        int x1 = 0;
        int x2 = 0;
        int y1 = 0;
        int y2 = 0;
        int evenOffset = 4;
        int oddOffset = 4;
        int startDelay = -1;
        int stopDelay = -1;
        boolean forced = false;

        int sequence = u16(spu, 2);
        while (true) {
            int delay = u16(spu, sequence);
            int nextSequence = u16(spu, sequence + 2);
            int p = sequence + 4;
            boolean end = false;
            while (!end && p < spu.length) {
                int command = spu[p++] & 0xFF;
                switch (command) {
                    case 0x00 -> {
                        forced = true;
                        startDelay = delay;
                    }
                    case 0x01 -> startDelay = delay;
                    case 0x02 -> stopDelay = delay;
                    case 0x03 -> {
                        colors = nibbles(spu, p);
                        p += 2;
                    }
                    case 0x04 -> {
                        alpha = nibbles(spu, p);
                        p += 2;
                    }
                    case 0x05 -> {
                        x1 = ((spu[p] & 0xFF) << 4) | ((spu[p + 1] & 0xFF) >> 4);
                        x2 = ((spu[p + 1] & 0x0F) << 8) | (spu[p + 2] & 0xFF);
                        y1 = ((spu[p + 3] & 0xFF) << 4) | ((spu[p + 4] & 0xFF) >> 4);
                        y2 = ((spu[p + 4] & 0x0F) << 8) | (spu[p + 5] & 0xFF);
                        p += 6;
                    }
                    case 0x06 -> {
                        evenOffset = u16(spu, p);
                        oddOffset = u16(spu, p + 2);
                        p += 4;
                    }
                    default -> end = true; // 0xFF, or something we don't know the length of
                }
            }
            if (nextSequence == sequence || nextSequence + 4 > spu.length) {
                break;
            }
            sequence = nextSequence;
        }

        int w = x2 - x1 + 1;
        int h = y2 - y1 + 1;
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
            return null;
        }
        int[] lut = lookupTable(colors, alpha, discPalette);
        int[] argb = new int[w * h];
        decodeField(spu, evenOffset, 0, w, h, lut, argb);
        decodeField(spu, oddOffset, 1, w, h, lut, argb);

        long start = timestampMs + delayMs(Math.max(startDelay, 0));
        long stop = stopDelay > 0 ? timestampMs + delayMs(stopDelay) : start;
        return BitmapCue.cropped(start, stop, x1, y1, w, h, forced, argb);
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
            if (discPalette != null) {
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
        int nibble = offset * 2;
        int limit = spu.length * 2;
        for (int y = firstRow; y < h; y += 2) {
            int x = 0;
            while (x < w && nibble < limit) {
                int v = nibbleAt(spu, nibble++);
                if (v < 0x4) {
                    v = (v << 4) | nibbleAt(spu, nibble++);
                    if (v < 0x10) {
                        v = (v << 4) | nibbleAt(spu, nibble++);
                        if (v < 0x40) {
                            v = (v << 4) | nibbleAt(spu, nibble++);
                        }
                    }
                }
                int run = v >> 2;
                if (run == 0 || run > w - x) {
                    run = w - x;
                }
                int pixel = lut[v & 3];
                if (pixel != 0) {
                    for (int col = x; col < x + run; col++) {
                        out[y * w + col] = pixel;
                    }
                }
                x += run;
            }
            nibble += nibble & 1;
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
