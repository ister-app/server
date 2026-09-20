package app.ister.transcoder.bitmapsub;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Blu-ray PGS stream in {@code .sup} form (what
 * {@code ffmpeg -c:s copy -f sup} writes) into bitmap cues.
 *
 * <p>A {@code .sup} is a flat list of segments, each behind a 13-byte header
 * ({@code "PG"}, 90 kHz PTS, DTS, type, size). A <em>display set</em> runs from
 * a presentation composition (PCS) to an END segment and carries the palette
 * (PDS) and RLE-compressed objects (ODS) it needs. A display set has no
 * duration: the picture stays until the next display set replaces or clears
 * it, so a cue's end is the PTS of the display set that follows.</p>
 *
 * <p>Damaged input degrades to "the cues up to there" rather than failing.</p>
 */
@Slf4j
public final class PgsParser {

    private static final int SEGMENT_PALETTE = 0x14;
    private static final int SEGMENT_OBJECT = 0x15;
    private static final int SEGMENT_COMPOSITION = 0x16;
    private static final int SEGMENT_END = 0x80;

    private static final int HEADER_SIZE = 13;
    /** A cue the stream never closes (truncated file) still ends at some point. */
    private static final long DANGLING_CUE_MS = 5000;

    private PgsParser() {
    }

    public static BitmapSubtitle parse(Path sup) throws IOException {
        return parse(Files.readAllBytes(sup));
    }

    static BitmapSubtitle parse(byte[] data) {
        State state = new State();
        ByteBuffer buf = ByteBuffer.wrap(data);
        try {
            while (buf.remaining() >= HEADER_SIZE) {
                if (buf.get() != 'P' || buf.get() != 'G') {
                    log.warn("PGS stream lost sync at byte {}, keeping {} cues", buf.position() - 2, state.cues.size());
                    break;
                }
                long pts = buf.getInt() & 0xFFFFFFFFL;
                buf.getInt(); // DTS
                int type = buf.get() & 0xFF;
                int size = buf.getShort() & 0xFFFF;
                if (size > buf.remaining()) {
                    break; // truncated tail
                }
                ByteBuffer segment = buf.slice(buf.position(), size);
                buf.position(buf.position() + size);
                state.accept(type, pts / 90, segment);
            }
        } catch (RuntimeException e) {
            log.warn("PGS stream is malformed, keeping {} cues: {}", state.cues.size(), e.toString());
        }
        state.closeOpenCues(-1);
        return new BitmapSubtitle(state.width, state.height, state.cues);
    }

    private record ObjectRef(int objectId, int x, int y, boolean forced,
                             boolean cropped, int cropX, int cropY, int cropW, int cropH) {
    }

    private record Composition(long ptsMs, int paletteId, List<ObjectRef> refs) {
    }

    private static final class PgsObject {
        int w;
        int h;
        byte[] rle;
        int filled;
    }

    private static final class State {
        final Map<Integer, int[]> palettes = new HashMap<>();
        final Map<Integer, PgsObject> objects = new HashMap<>();
        final List<BitmapCue> cues = new ArrayList<>();
        /** Indexes into {@link #cues} of the pictures currently on screen. */
        final List<Integer> open = new ArrayList<>();
        Composition composition;
        int width;
        int height;

        void accept(int type, long ptsMs, ByteBuffer seg) {
            switch (type) {
                case SEGMENT_COMPOSITION -> readComposition(ptsMs, seg);
                case SEGMENT_PALETTE -> readPalette(seg);
                case SEGMENT_OBJECT -> readObject(seg);
                case SEGMENT_END -> endDisplaySet();
                default -> { /* window definitions carry nothing we need */ }
            }
        }

        private void readComposition(long ptsMs, ByteBuffer seg) {
            width = seg.getShort() & 0xFFFF;
            height = seg.getShort() & 0xFFFF;
            seg.get(); // frame rate
            seg.getShort(); // composition number
            int compositionState = seg.get() & 0xFF;
            seg.get(); // palette update flag: the objects stay cached, so a re-render picks the new palette up
            int paletteId = seg.get() & 0xFF;
            int count = seg.get() & 0xFF;
            if ((compositionState & 0xC0) != 0) {
                objects.clear(); // epoch start / acquisition point: nothing carries over
            }
            List<ObjectRef> refs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int objectId = seg.getShort() & 0xFFFF;
                seg.get(); // window id
                int flags = seg.get() & 0xFF;
                int x = seg.getShort() & 0xFFFF;
                int y = seg.getShort() & 0xFFFF;
                boolean cropped = (flags & 0x80) != 0;
                int cx = 0;
                int cy = 0;
                int cw = 0;
                int ch = 0;
                if (cropped) {
                    cx = seg.getShort() & 0xFFFF;
                    cy = seg.getShort() & 0xFFFF;
                    cw = seg.getShort() & 0xFFFF;
                    ch = seg.getShort() & 0xFFFF;
                }
                refs.add(new ObjectRef(objectId, x, y, (flags & 0x40) != 0, cropped, cx, cy, cw, ch));
            }
            composition = new Composition(ptsMs, paletteId, refs);
        }

        private void readPalette(ByteBuffer seg) {
            int id = seg.get() & 0xFF;
            seg.get(); // version
            int[] palette = palettes.computeIfAbsent(id, _ -> new int[256]);
            boolean hd = height >= 720;
            while (seg.remaining() >= 5) {
                int entry = seg.get() & 0xFF;
                int y = seg.get() & 0xFF;
                int cr = seg.get() & 0xFF;
                int cb = seg.get() & 0xFF;
                int alpha = seg.get() & 0xFF;
                palette[entry] = toArgb(y, cb, cr, alpha, hd);
            }
        }

        private void readObject(ByteBuffer seg) {
            int id = seg.getShort() & 0xFFFF;
            seg.get(); // version
            int sequence = seg.get() & 0xFF;
            if ((sequence & 0x80) != 0) { // first fragment: length (3 bytes, includes w+h), then w, h
                int length = ((seg.get() & 0xFF) << 16) | (seg.getShort() & 0xFFFF);
                PgsObject object = new PgsObject();
                object.w = seg.getShort() & 0xFFFF;
                object.h = seg.getShort() & 0xFFFF;
                object.rle = new byte[Math.max(0, length - 4)];
                objects.put(id, object);
            }
            PgsObject object = objects.get(id);
            if (object == null) {
                return; // continuation of an object whose start we never saw
            }
            int n = Math.min(seg.remaining(), object.rle.length - object.filled);
            seg.get(object.rle, object.filled, n);
            object.filled += n;
        }

        private void endDisplaySet() {
            if (composition == null) {
                return;
            }
            closeOpenCues(composition.ptsMs());
            int[] palette = palettes.getOrDefault(composition.paletteId(), new int[256]);
            for (ObjectRef ref : composition.refs()) {
                PgsObject object = objects.get(ref.objectId());
                if (object == null || object.w == 0 || object.h == 0) {
                    continue;
                }
                BitmapCue cue = render(object, ref, palette, composition.ptsMs());
                if (cue != null) {
                    open.add(cues.size());
                    cues.add(cue);
                }
            }
            composition = null;
        }

        /** Ends the pictures on screen at {@code ptsMs}; negative means "the stream ran out". */
        void closeOpenCues(long ptsMs) {
            for (int index : open) {
                BitmapCue cue = cues.get(index);
                cues.set(index, cue.withEnd(ptsMs < 0 ? cue.startMs() + DANGLING_CUE_MS : ptsMs));
            }
            open.clear();
        }

        private static BitmapCue render(PgsObject object, ObjectRef ref, int[] palette, long ptsMs) {
            int w = object.w;
            int h = object.h;
            int[] argb = new int[w * h];
            decodeRle(object.rle, object.filled, w, h, palette, argb);
            int x = ref.x();
            int y = ref.y();
            if (ref.cropped() && ref.cropW() > 0 && ref.cropH() > 0
                    && ref.cropX() + ref.cropW() <= w && ref.cropY() + ref.cropH() <= h) {
                int[] cropped = new int[ref.cropW() * ref.cropH()];
                for (int row = 0; row < ref.cropH(); row++) {
                    System.arraycopy(argb, (ref.cropY() + row) * w + ref.cropX(), cropped, row * ref.cropW(), ref.cropW());
                }
                argb = cropped;
                w = ref.cropW();
                h = ref.cropH();
            }
            return BitmapCue.cropped(ptsMs, ptsMs, x, y, w, h, ref.forced(), argb);
        }

        /**
         * PGS run-length coding: a non-zero byte is one pixel of that colour; a
         * zero byte introduces a run whose flag byte says whether the length is
         * 6 or 14 bits and whether a colour follows (no colour = colour 0).
         * {@code 00 00} ends the line.
         */
        private static void decodeRle(byte[] rle, int length, int w, int h, int[] palette, int[] out) {
            int i = 0;
            int x = 0;
            int y = 0;
            while (i < length && y < h) {
                int b = rle[i++] & 0xFF;
                if (b != 0) {
                    if (x < w) {
                        out[y * w + x] = palette[b];
                    }
                    x++;
                    continue;
                }
                if (i >= length) {
                    break;
                }
                int flags = rle[i++] & 0xFF;
                if (flags == 0) {
                    x = 0;
                    y++;
                    continue;
                }
                int run = flags & 0x3F;
                if ((flags & 0x40) != 0 && i < length) {
                    run = (run << 8) | (rle[i++] & 0xFF);
                }
                int color = 0;
                if ((flags & 0x80) != 0 && i < length) {
                    color = rle[i++] & 0xFF;
                }
                int end = Math.min(w, x + run);
                int pixel = palette[color];
                if (pixel != 0) {
                    for (int col = x; col < end; col++) {
                        out[y * w + col] = pixel;
                    }
                }
                x += run;
            }
        }

        /** Limited-range YCbCr to ARGB; BT.709 for HD canvases, BT.601 below that. */
        private static int toArgb(int y, int cb, int cr, int alpha, boolean hd) {
            if (alpha == 0) {
                return 0;
            }
            double luma = 1.164383 * (y - 16);
            double pb = cb - 128.0;
            double pr = cr - 128.0;
            double r;
            double g;
            double b;
            if (hd) {
                r = luma + 1.792741 * pr;
                g = luma - 0.213249 * pb - 0.532909 * pr;
                b = luma + 2.112402 * pb;
            } else {
                r = luma + 1.596027 * pr;
                g = luma - 0.391762 * pb - 0.812968 * pr;
                b = luma + 2.017232 * pb;
            }
            return (alpha << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }

        private static int clamp(double v) {
            return (int) Math.max(0, Math.min(255, Math.round(v)));
        }
    }
}
