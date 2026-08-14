package app.ister.disk.events.mediafilefound;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaFileFoundEpisodeBoundariesTest {

    private static final long HOUR_AND_HALF = 5_400_000L;

    @Test
    void oneChapterPerEpisodeIsUsedDirectly() {
        var starts = MediaFileFoundEpisodeBoundaries.boundariesFromChapters(
                List.of(0L, 2_650_000L), HOUR_AND_HALF, 2);
        assertEquals(List.of(0L, 2_650_000L), starts);
    }

    @Test
    void oneChapterPerEpisodeIsNormalizedToStartAtZero() {
        var starts = MediaFileFoundEpisodeBoundaries.boundariesFromChapters(
                List.of(5_000L, 2_650_000L), HOUR_AND_HALF, 2);
        assertEquals(List.of(0L, 2_650_000L), starts);
    }

    @Test
    void sceneMarkersPickTheChapterNearestToTheEqualSplitPoint() {
        // 6 scene markers, 2 episodes: the marker nearest to the file midpoint (2,700,000) wins.
        var starts = MediaFileFoundEpisodeBoundaries.boundariesFromChapters(
                List.of(0L, 900_000L, 1_800_000L, 2_640_000L, 3_600_000L, 4_500_000L), HOUR_AND_HALF, 2);
        assertEquals(List.of(0L, 2_640_000L), starts);
    }

    @Test
    void sceneMarkersThatWouldMakeAnEpisodeImplausiblyShortFallBackToEqualSplit() {
        // Nearest marker to the midpoint sits at 96% of the file: falls back to an equal split.
        var starts = MediaFileFoundEpisodeBoundaries.boundariesFromChapters(
                List.of(0L, 5_200_000L, 5_300_000L), HOUR_AND_HALF, 2);
        assertEquals(List.of(0L, 2_700_000L), starts);
    }

    @Test
    void noChaptersSplitsEqually() {
        var starts = MediaFileFoundEpisodeBoundaries.boundariesFromChapters(List.of(), 5_400_000L, 3);
        assertEquals(List.of(0L, 1_800_000L, 3_600_000L), starts);
    }

    @Test
    void fewerChaptersThanEpisodesSplitsEqually() {
        var starts = MediaFileFoundEpisodeBoundaries.boundariesFromChapters(List.of(0L), 5_400_000L, 2);
        assertEquals(List.of(0L, 2_700_000L), starts);
    }
}
