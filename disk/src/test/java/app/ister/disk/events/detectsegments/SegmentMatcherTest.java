package app.ister.disk.events.detectsegments;

import app.ister.disk.events.detectsegments.SegmentMatcher.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentMatcherTest {

    private static final long HOP_MS = ChromaFingerprinter.hopMillis(AudioPcmReader.SAMPLE_RATE);

    @Test
    void findsThePlantedCommonRunAtAnOffset() {
        int[] shared = randomHashes(200, 1);
        int[] a = randomHashes(1000, 2);
        int[] b = randomHashes(1000, 3);
        System.arraycopy(shared, 0, a, 100, shared.length);
        System.arraycopy(shared, 0, b, 400, shared.length);

        Optional<Segment> run = SegmentMatcher.longestCommonRun(a, b, HOP_MS);

        assertTrue(run.isPresent());
        assertEquals(100 * HOP_MS, run.get().startMs());
        assertEquals(300 * HOP_MS, run.get().endMs());
    }

    @Test
    void toleratesShortGapsInsideTheRun() {
        int[] shared = randomHashes(200, 1);
        int[] a = randomHashes(600, 2);
        int[] b = randomHashes(600, 3);
        System.arraycopy(shared, 0, a, 50, shared.length);
        System.arraycopy(shared, 0, b, 50, shared.length);
        // Corrupt a few frames mid-run in one episode (an ad-libbed line over the theme).
        a[140] = ~a[140];
        a[141] = ~a[141];

        Optional<Segment> run = SegmentMatcher.longestCommonRun(a, b, HOP_MS);

        assertTrue(run.isPresent());
        assertEquals(200 * HOP_MS, run.get().lengthMs(), "the gap must not split the run");
    }

    @Test
    void unrelatedEpisodesShareNothing() {
        Optional<Segment> run = SegmentMatcher.longestCommonRun(
                randomHashes(1000, 4), randomHashes(1000, 5), HOP_MS);
        assertTrue(run.isEmpty());
    }

    @Test
    void emptyFingerprintsShareNothing() {
        assertTrue(SegmentMatcher.longestCommonRun(new int[0], randomHashes(100, 1), HOP_MS).isEmpty());
    }

    @Test
    void matchedFingerprintsFromRealAudioFindTheSharedPrefix() {
        // Two "episodes": the same 20 s intro melody followed by different content.
        short[] intro = ChromaFingerprinterTest.melody(20_000, 1);
        short[] episodeA = concat(intro, ChromaFingerprinterTest.melody(40_000, 2));
        short[] episodeB = concat(intro, ChromaFingerprinterTest.melody(40_000, 3));

        Optional<Segment> run = SegmentMatcher.longestCommonRun(
                ChromaFingerprinter.fingerprint(episodeA, AudioPcmReader.SAMPLE_RATE),
                ChromaFingerprinter.fingerprint(episodeB, AudioPcmReader.SAMPLE_RATE),
                HOP_MS);

        assertTrue(run.isPresent());
        assertTrue(run.get().startMs() <= 1_000, "run should start at the beginning, was " + run.get().startMs());
        assertTrue(Math.abs(run.get().endMs() - 20_000) <= 2_000,
                "run should end near 20s, was " + run.get().endMs());
    }

    @Test
    void aggregateNeedsEnoughSupport() {
        assertTrue(SegmentMatcher.aggregate(List.of(new Segment(10_000, 40_000)), 2).isEmpty());
        assertTrue(SegmentMatcher.aggregate(List.of(new Segment(10_000, 40_000)), 1).isPresent());
    }

    @Test
    void aggregateTakesTheMedianSoOneOddPairingCannotSetTheBounds() {
        Optional<Segment> segment = SegmentMatcher.aggregate(List.of(
                new Segment(10_000, 40_000),
                new Segment(10_500, 40_500),
                new Segment(90_000, 200_000)), 2);
        assertTrue(segment.isPresent());
        assertEquals(10_500, segment.get().startMs());
        assertEquals(40_500, segment.get().endMs());
    }

    @Test
    void aggregateSnapsANearZeroStartToZero() {
        Optional<Segment> segment = SegmentMatcher.aggregate(List.of(
                new Segment(3_000, 40_000), new Segment(4_000, 41_000)), 2);
        assertTrue(segment.isPresent());
        assertEquals(0, segment.get().startMs());
    }

    private static int[] randomHashes(int length, long seed) {
        Random random = new Random(seed);
        int[] hashes = new int[length];
        for (int i = 0; i < length; i++) {
            hashes[i] = random.nextInt();
        }
        return hashes;
    }

    private static short[] concat(short[] a, short[] b) {
        short[] result = new short[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
