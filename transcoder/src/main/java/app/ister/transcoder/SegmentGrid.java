package app.ister.transcoder;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * The segment boundaries of one stream: where every segment starts, and where
 * the last one ends.
 * <p>
 * The grid comes from the video keyframes, but each stream ends somewhere else,
 * and a cut near or past the end of a stream can produce no file at all — the
 * segment muxer cuts at the first keyframe at or after the requested time, and
 * where there is none left it simply never opens that segment. The playlist
 * would then advertise a segment that can never be served, which is a permanent
 * failure dressed up as a temporary one.
 * <p>
 * Dropping such a boundary costs nothing: the grid feeds the pass as well as the
 * playlist, so the segment is not orphaned — the previous one runs to the end of
 * the stream instead. Verified by packet count: a trimmed grid carries every
 * packet the source has.
 */
@Slf4j
public record SegmentGrid(List<Double> starts, double end) {

    /**
     * A boundary this close to the end of a stream is not worth advertising:
     * whether the muxer still opens a segment there depends on the cut tolerance
     * ({@code -segment_time_delta 0.05}) and on timestamp rounding, and a
     * boundary that turns out not to split leaves the playlist promising a file
     * that will never exist.
     * <p>
     * This is an epsilon against a <i>measured</i> stream end, not a guess at how
     * long a tail "should" be — in the observed failures the remaining tail is
     * 0.0001s (a copy cut landing on the last keyframe) or negative (audio cut
     * past its own end), while healthy files leave one to three seconds. Three
     * orders of magnitude of margin, and erring on the side of trimming only
     * makes the previous segment longer.
     */
    static final double MIN_TAIL_SECONDS = 0.25;

    /** Interval of the synthetic grid used for files without a video stream. */
    static final double SYNTHETIC_INTERVAL_SECONDS = 10.0;

    /**
     * Trims {@code cutCandidates} to what a pass over a stream ending at
     * {@code streamEnd} can actually produce.
     *
     * @param streamEnd measured end of this stream, or NaN when it could not be
     *                  measured — then the container duration is used and nothing
     *                  is trimmed, which is exactly the behaviour from before
     *                  this existed. A probe that fails must never shorten a
     *                  playlist.
     */
    public static SegmentGrid trim(List<Double> cutCandidates, double streamEnd, double totalDuration) {
        return trim(cutCandidates, streamEnd, streamEnd, totalDuration);
    }

    /**
     * @param trimEnd     the last position a cut can still land a segment on
     * @param playlistEnd where the stream really ends — the last segment plays to
     *                    here. For a video copy the two differ: a cut on the last
     *                    keyframe may not split, while the segment itself does run
     *                    on to the final packet.
     */
    public static SegmentGrid trim(List<Double> cutCandidates, double trimEnd, double playlistEnd,
                                   double totalDuration) {
        double end = usableEnd(trimEnd, totalDuration);
        List<Double> candidates = cutCandidates.isEmpty()
                ? synthetic(end)
                : cutCandidates;

        // Nothing usable to trim against (both the probe and the duration are
        // unknown, so end is zero or NaN): keep every boundary. Guessing here
        // would drop real segments.
        if (Double.isNaN(end) || end <= 0) {
            return new SegmentGrid(List.copyOf(candidates), usableEnd(playlistEnd, totalDuration));
        }

        List<Double> starts = new ArrayList<>();
        for (Double start : candidates) {
            // Keep the first start no matter what: a stream always has one segment.
            if (starts.isEmpty() || end - start >= MIN_TAIL_SECONDS) {
                starts.add(start);
            }
        }
        int dropped = candidates.size() - starts.size();
        if (dropped > 0) {
            log.debug("Trimmed {} segment boundaries past the end of the stream ({}s)", dropped, end);
        }
        return new SegmentGrid(List.copyOf(starts), usableEnd(playlistEnd, totalDuration));
    }

    /** The boundaries FFmpeg cuts at: every start except the implicit 0.0. */
    public List<Double> cutTimes() {
        return starts.size() <= 1 ? List.of() : List.copyOf(starts.subList(1, starts.size()));
    }

    public int segmentCount() {
        return starts.size();
    }

    private static double usableEnd(double streamEnd, double totalDuration) {
        if (Double.isNaN(streamEnd) || streamEnd <= 0) return totalDuration;
        // A probe reporting past the container is measuring something we do not
        // understand; trust the container rather than extend beyond it.
        return Math.min(streamEnd, totalDuration);
    }

    /**
     * The grid for a file whose keyframes are unknown (audio-only files never get
     * a video probe). The pass and the playlist must agree on it, so it is built
     * here rather than in either of them.
     */
    private static List<Double> synthetic(double totalDuration) {
        List<Double> starts = new ArrayList<>();
        for (double t = 0; t < totalDuration; t += SYNTHETIC_INTERVAL_SECONDS) {
            starts.add(t);
        }
        if (starts.isEmpty()) starts.add(0.0);
        return starts;
    }
}
