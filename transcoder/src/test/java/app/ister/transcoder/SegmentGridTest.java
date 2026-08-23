package app.ister.transcoder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule this enforces: a playlist may only advertise segments FFmpeg will
 * actually write. A cut at or past the end of a stream produces no file, and the
 * resulting entry is unservable forever.
 */
class SegmentGridTest {

    @Test
    void healthyTailKeepsEveryBoundary() {
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2.0, 4.0), 7.15, 7.2);

        assertEquals(List.of(0.0, 2.0, 4.0), grid.starts());
        assertEquals(7.15, grid.end());
        assertEquals(List.of(2.0, 4.0), grid.cutTimes());
    }

    @Test
    void aCutOnTheVeryEndOfTheStreamIsDropped() {
        // The video-copy case: the cut lands on the last keyframe, and the muxer
        // has nothing after it to cut at.
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2616.73, 2619.15), 2619.15, 2619.65);

        assertEquals(List.of(0.0, 2616.73), grid.starts());
        assertEquals(List.of(2616.73), grid.cutTimes());
    }

    @Test
    void theLastSegmentPlaysToTheEndOfTheStreamEvenWhenTrimmedEarlier() {
        // A copy is trimmed at the last keyframe but the segment itself runs on to
        // the final packet, so the playlist duration follows that, not the cut.
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2616.73, 2619.15), 2619.15, 2619.617, 2619.65);

        assertEquals(List.of(0.0, 2616.73), grid.starts());
        assertEquals(2619.617, grid.end());
    }

    @Test
    void cutsPastTheEndOfAShorterStreamAreDropped() {
        // The audio case: the grid comes from the video keyframes, but this track
        // stopped seconds earlier.
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2.0, 4.0, 6.0, 8.0), 5.0, 10.0);

        assertEquals(List.of(0.0, 2.0, 4.0), grid.starts());
        assertEquals(5.0, grid.end());
    }

    @Test
    void aTailJustOverTheMinimumIsKept() {
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2.0), 2.30, 3.0);

        assertEquals(List.of(0.0, 2.0), grid.starts());
    }

    @Test
    void aTailUnderTheMinimumIsDropped() {
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2.0), 2.10, 3.0);

        assertEquals(List.of(0.0), grid.starts());
        assertTrue(grid.cutTimes().isEmpty());
    }

    @Test
    void aStreamAlwaysKeepsOneSegment() {
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2.0, 4.0), 0.01, 10.0);

        assertEquals(List.of(0.0), grid.starts());
        assertEquals(1, grid.segmentCount());
    }

    @Test
    void anUnmeasurableStreamEndFallsBackToTheContainerDuration() {
        // A probe that failed must never shorten a playlist — that is the whole
        // reason it is allowed to fail.
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2.0, 4.0), Double.NaN, 6.0);

        assertEquals(List.of(0.0, 2.0, 4.0), grid.starts());
        assertEquals(6.0, grid.end());
    }

    @Test
    void aStreamEndPastTheContainerIsClamped() {
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2.0), 99.0, 4.0);

        assertEquals(4.0, grid.end());
        assertEquals(List.of(0.0, 2.0), grid.starts());
    }

    @Test
    void withoutAnyUsableEndNothingIsTrimmed() {
        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 2.0, 4.0), Double.NaN, 0.0);

        assertEquals(List.of(0.0, 2.0, 4.0), grid.starts());
    }

    @Test
    void aFileWithoutKeyframesGetsTheSyntheticGridThePassAlsoUses() {
        SegmentGrid grid = SegmentGrid.trim(List.of(), Double.NaN, 30.0);

        assertEquals(List.of(0.0, 10.0, 20.0), grid.starts());
        assertEquals(List.of(10.0, 20.0), grid.cutTimes());
    }

    @Test
    void aFileShorterThanOneSyntheticSegmentStillGetsOne() {
        SegmentGrid grid = SegmentGrid.trim(List.of(), Double.NaN, 4.0);

        assertEquals(List.of(0.0), grid.starts());
        assertTrue(grid.cutTimes().isEmpty());
    }
}
