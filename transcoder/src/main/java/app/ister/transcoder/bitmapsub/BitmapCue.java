package app.ister.transcoder.bitmapsub;

import java.util.Arrays;
import java.util.Objects;

/**
 * One subtitle picture: when it is on screen, where it sits on the subtitle
 * canvas, and its pixels as non-premultiplied ARGB ({@code w * h} entries,
 * row-major). Times are milliseconds on the source file's own timeline.
 */
public record BitmapCue(long startMs, long endMs, int x, int y, int w, int h, boolean forced, int[] argb) {

    @Override
    public boolean equals(Object o) {
        return o instanceof BitmapCue(long otherStart, long otherEnd, int otherX, int otherY, int otherW, int otherH,
                                      boolean otherForced, int[] otherArgb)
                && startMs == otherStart && endMs == otherEnd
                && x == otherX && y == otherY && w == otherW && h == otherH
                && forced == otherForced && Arrays.equals(argb, otherArgb);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(startMs, endMs, x, y, w, h, forced) + Arrays.hashCode(argb);
    }

    /** Without the pixels: a cue is tens of thousands of them. */
    @Override
    public String toString() {
        return "BitmapCue[" + startMs + "-" + endMs + " ms, " + w + "x" + h + " @" + x + "," + y
                + (forced ? ", forced]" : "]");
    }

    BitmapCue withEnd(long newEndMs) {
        return new BitmapCue(startMs, newEndMs, x, y, w, h, forced, argb);
    }

    /**
     * Builds a cue trimmed to the bounding box of its visible pixels, so the
     * sprite sheets carry no transparent padding (DVD pictures are often a
     * full-width band around two short lines). Returns {@code null} when
     * nothing is visible at all.
     */
    static BitmapCue cropped(long startMs, long endMs, int x, int y, int w, int h, boolean forced, int[] argb) {
        int top = -1;
        int bottom = -1;
        int left = w;
        int right = -1;
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                if ((argb[row * w + col] >>> 24) != 0) {
                    if (top < 0) {
                        top = row;
                    }
                    bottom = row;
                    left = Math.min(left, col);
                    right = Math.max(right, col);
                }
            }
        }
        if (top < 0) {
            return null;
        }
        int cw = right - left + 1;
        int ch = bottom - top + 1;
        if (cw == w && ch == h) {
            return new BitmapCue(startMs, endMs, x, y, w, h, forced, argb);
        }
        int[] trimmed = new int[cw * ch];
        for (int row = 0; row < ch; row++) {
            System.arraycopy(argb, (top + row) * w + left, trimmed, row * cw, cw);
        }
        return new BitmapCue(startMs, endMs, x + left, y + top, cw, ch, forced, trimmed);
    }
}
