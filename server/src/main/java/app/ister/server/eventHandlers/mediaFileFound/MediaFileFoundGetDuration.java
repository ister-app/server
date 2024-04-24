package app.ister.server.eventHandlers.mediaFileFound;

import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.NullOutput;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MediaFileFoundGetDuration {
    private final FFmpeg ffmpeg;
    private final FFprobe ffprobe;

    private final List<String> durationString = List.of("DURATION", "DURATION-eng");

    public MediaFileFoundGetDuration(FFmpeg ffmpeg, FFprobe ffprobe) {
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
    }

    /**
     * Given the path is from a media file.
     * - It first checks the media streams from the file for duration.
     * - If it not contains a duration it will get the media file with ffmpeg wich contains longer.
     */
    public long getDuration(String path) {
        long duration = getDurationFromStream(path);
        if (duration == 0) {
            return getDurationWithLoadingFile(path);
        } else {
            return duration;
        }
    }

    private long getDurationFromStream(String path) {
        FFprobeResult result = ffprobe.setShowStreams(true).setInput(path).execute();
        List<Long> longList = new ArrayList<>();
        result.getStreams().forEach(stream -> {
            if (stream.getDuration() != null) {
                // Get the duration in seconds and multiply with 1000 for milliseconds.
                longList.add(stream.getDuration().longValue() * 1000);
            }
            durationString.forEach(durationString -> {
                if (stream.getTag(durationString) != null) {
                    longList.add(LocalTime.parse(stream.getTag(durationString)).getLong(ChronoField.MILLI_OF_DAY));
                }
            });

        });

        if (longList.isEmpty()) {
            return 0L;
        } else {
            Collections.sort(longList);
            return Collections.max(longList);
        }

    }

    private long getDurationWithLoadingFile(String path) {
        final AtomicLong durationMillis = new AtomicLong();
        ffmpeg
                .addInput(
                        UrlInput.fromUrl(path)
                )
                .addOutput(new NullOutput())
                .setProgressListener(progress -> durationMillis.set(progress.getTimeMillis()))
                .execute();
        return durationMillis.get();
    }
}
