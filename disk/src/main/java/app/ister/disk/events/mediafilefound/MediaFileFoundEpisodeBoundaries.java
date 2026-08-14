package app.ister.disk.events.mediafilefound;

import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffprobe.Chapter;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes where each episode starts inside a multi-episode media file (s04e06-e07.mkv).
 *
 * <p>Prefers the MKV chapter markers; when those don't line up with the episode count the file is
 * split into equal parts.
 */
@Component
@Slf4j
public class MediaFileFoundEpisodeBoundaries {

    // A boundary picked from scene-marker chapters may not shrink an episode below this fraction
    // of the expected (equal-split) episode length.
    private static final double MIN_SEGMENT_FRACTION = 0.2;

    /**
     * Start time in milliseconds of every episode in the file, in order. The first element is
     * always 0 and the list has exactly {@code episodeCount} elements.
     */
    public List<Long> boundaryStarts(String path, String dirOfFFmpeg, long durationInMilliseconds, int episodeCount) {
        List<Long> chapterStarts;
        try {
            chapterStarts = FFprobe.atPath(Paths.get(dirOfFFmpeg))
                    .setShowChapters(true)
                    .setInput(path)
                    .setLogLevel(LogLevel.ERROR)
                    .execute()
                    .getChapters().stream()
                    .map(MediaFileFoundEpisodeBoundaries::chapterStartMs)
                    .sorted()
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Could not read chapters of {}, splitting equally: {}", path, e.getMessage());
            chapterStarts = List.of();
        }
        return boundariesFromChapters(chapterStarts, durationInMilliseconds, episodeCount);
    }

    /** Pure mapping from chapter starts to episode starts; package-private for tests. */
    static List<Long> boundariesFromChapters(List<Long> chapterStarts, long durationInMilliseconds, int episodeCount) {
        if (chapterStarts.size() == episodeCount) {
            // One chapter per episode: use them directly, normalized to start at 0.
            List<Long> starts = new ArrayList<>(chapterStarts);
            starts.set(0, 0L);
            return starts;
        }
        if (chapterStarts.size() > episodeCount) {
            // Scene markers: for every boundary pick the chapter closest to the equal-split point,
            // as long as that keeps the boundaries increasing and no episode implausibly short.
            List<Long> starts = pickNearestChapters(chapterStarts, durationInMilliseconds, episodeCount);
            if (starts != null) {
                return starts;
            }
        }
        return equalSplit(durationInMilliseconds, episodeCount);
    }

    private static List<Long> pickNearestChapters(List<Long> chapterStarts, long durationInMilliseconds, int episodeCount) {
        long expectedLength = durationInMilliseconds / episodeCount;
        List<Long> starts = new ArrayList<>(List.of(0L));
        for (int boundary = 1; boundary < episodeCount; boundary++) {
            long target = durationInMilliseconds * boundary / episodeCount;
            long nearest = chapterStarts.stream()
                    .min((a, b) -> Long.compare(Math.abs(a - target), Math.abs(b - target)))
                    .orElse(target);
            long previous = starts.getLast();
            if (nearest - previous < MIN_SEGMENT_FRACTION * expectedLength
                    || durationInMilliseconds - nearest < MIN_SEGMENT_FRACTION * expectedLength) {
                return null;
            }
            starts.add(nearest);
        }
        return starts;
    }

    private static List<Long> equalSplit(long durationInMilliseconds, int episodeCount) {
        List<Long> starts = new ArrayList<>();
        for (int boundary = 0; boundary < episodeCount; boundary++) {
            starts.add(durationInMilliseconds * boundary / episodeCount);
        }
        return starts;
    }

    private static long chapterStartMs(Chapter chapter) {
        Double startTime = chapter.getStartTime();
        return startTime != null ? Math.round(startTime * 1000) : 0L;
    }
}
