package app.ister.disk.events.detectsegments;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the audio segment two episodes share (the intro or the credits) by aligning their hash
 * sequences, and aggregates per-pair candidates into one segment per episode.
 */
final class SegmentMatcher {

    /** A frame pair with at most this Hamming distance counts towards an alignment's score. */
    static final int ALIGN_MAX_HAMMING = 6;

    /** Within the best alignment, frames up to this distance still extend the common run. */
    static final int RUN_MAX_HAMMING = 6;

    /** Mismatching gaps up to this many frames (~0.26 s) don't break a run. */
    static final int RUN_MAX_GAP = 2;

    /** Alignments matching fewer frames than this are noise, not a shared segment. */
    static final int MIN_ALIGN_SCORE = 40;

    /** A run's start within this many ms of 0 snaps to 0 (cold-open boundary jitter). */
    static final long SNAP_TO_START_MS = 5_000;

    private SegmentMatcher() {
    }

    /** A time range in one episode's window, in ms relative to the window start. */
    record Segment(long startMs, long endMs) {
        long lengthMs() {
            return endMs - startMs;
        }
    }

    /**
     * The longest audio run that {@code a} shares with {@code b}, in {@code a}'s window time.
     * Scans every alignment offset, then walks the best one for the longest gap-tolerant run.
     */
    static Optional<Segment> longestCommonRun(int[] a, int[] b, long hopMs) {
        if (a.length == 0 || b.length == 0) {
            return Optional.empty();
        }
        int bestOffset = 0;
        int bestScore = 0;
        for (int offset = -(b.length - 1); offset < a.length; offset++) {
            int score = 0;
            int from = Math.max(0, offset);
            int to = Math.min(a.length, b.length + offset);
            for (int i = from; i < to; i++) {
                if (Integer.bitCount(a[i] ^ b[i - offset]) <= ALIGN_MAX_HAMMING) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestOffset = offset;
            }
        }
        if (bestScore < MIN_ALIGN_SCORE) {
            return Optional.empty();
        }
        return longestRunAtOffset(a, b, bestOffset, hopMs);
    }

    private static Optional<Segment> longestRunAtOffset(int[] a, int[] b, int offset, long hopMs) {
        int from = Math.max(0, offset);
        int to = Math.min(a.length, b.length + offset);
        int bestStart = -1;
        int bestLength = 0;
        int runStart = -1;
        int lastMatch = -1;
        for (int i = from; i < to; i++) {
            if (Integer.bitCount(a[i] ^ b[i - offset]) <= RUN_MAX_HAMMING) {
                if (runStart < 0 || i - lastMatch > RUN_MAX_GAP + 1) {
                    runStart = i;
                }
                lastMatch = i;
                if (lastMatch - runStart + 1 > bestLength) {
                    bestLength = lastMatch - runStart + 1;
                    bestStart = runStart;
                }
            }
        }
        if (bestStart < 0) {
            return Optional.empty();
        }
        return Optional.of(new Segment(bestStart * hopMs, (bestStart + bestLength) * hopMs));
    }

    /**
     * One segment from the candidates its pairings produced: requires {@code minSupport}
     * agreeing candidates and takes the median start/end, so a single odd pairing (a recap
     * shared with only one neighbour) cannot set the bounds. A start within
     * {@link #SNAP_TO_START_MS} snaps to 0.
     */
    static Optional<Segment> aggregate(List<Segment> candidates, int minSupport) {
        if (candidates.size() < minSupport) {
            return Optional.empty();
        }
        long start = median(candidates.stream().map(Segment::startMs).toList());
        long end = median(candidates.stream().map(Segment::endMs).toList());
        if (start < SNAP_TO_START_MS) {
            start = 0;
        }
        if (end <= start) {
            return Optional.empty();
        }
        return Optional.of(new Segment(start, end));
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
}
