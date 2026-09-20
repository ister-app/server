package app.ister.disk.events.mediafilefound;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class MediaFileFoundCheckForStreams {

    private static final List<String> DURATION_TAGS = List.of("DURATION", "DURATION-eng");

    public record CheckResult(List<MediaFileStreamEntity> streams, boolean hasAttachedPic, long durationInMilliseconds) {}

    private static StreamCodecType codecTypeToEnum(String codecType) {
        return switch (codecType) {
            case "VIDEO" -> StreamCodecType.VIDEO;
            case "AUDIO" -> StreamCodecType.AUDIO;
            case "SUBTITLE" -> StreamCodecType.SUBTITLE;
            case "VIDEO_NOT_PICTURE" -> StreamCodecType.VIDEO_NOT_PICTURE;
            case "DATA" -> StreamCodecType.DATA;
            case "ATTACHMENT" -> StreamCodecType.ATTACHMENT;
            default -> StreamCodecType.UNKNOWN;
        };
    }

    /** @param input what ffprobe reads: the local path, or a URL for a remote or S3 file (see MediaFileInputResolver) */
    public CheckResult checkForStreams(MediaFileEntity mediaFileEntity, String input, String dirOfFFmpeg) {
        List<MediaFileStreamEntity> result = new ArrayList<>();
        List<Long> durationList = new ArrayList<>();
        boolean hasAttachedPic = false;
        FFprobeResult mediaStreams = FFprobe.atPath(Paths.get(dirOfFFmpeg))
                .setShowStreams(true)
                // Blu-ray rips carry PGS subtitle streams whose first packet sits well past the
                // default 5 MB probe window; without a wider look ffprobe reports them without
                // codec parameters (and warns about it on every file).
                .setProbeSize(50_000_000L)
                .setAnalyzeDuration(20_000_000L)
                .setInput(input)
                .setLogLevel(LogLevel.ERROR)
                .execute();

        for (com.github.kokorin.jaffree.ffprobe.Stream stream : mediaStreams.getStreams()) {
            collectDurations(stream, durationList);
            if (isAttachedPicture(stream)) {
                hasAttachedPic = true;
            } else {
                result.add(toEntity(mediaFileEntity, stream));
            }
        }
        long duration = durationList.isEmpty() ? 0L : Collections.max(durationList);
        return new CheckResult(result, hasAttachedPic, duration);
    }

    /** A stream's own duration plus the container's per-stream duration tags; the longest one wins. */
    private static void collectDurations(com.github.kokorin.jaffree.ffprobe.Stream stream, List<Long> durations) {
        if (stream.getDuration() != null) {
            durations.add(Math.round(stream.getDuration().doubleValue() * 1000));
        }
        for (String tag : DURATION_TAGS) {
            String tagValue = stream.getTag(tag);
            if (tagValue != null) {
                durations.add(LocalTime.parse(tagValue).getLong(ChronoField.MILLI_OF_DAY));
            }
        }
    }

    private static boolean isAttachedPicture(com.github.kokorin.jaffree.ffprobe.Stream stream) {
        return stream.getDisposition() != null && Boolean.TRUE.equals(stream.getDisposition().getAttachedPic());
    }

    private static MediaFileStreamEntity toEntity(MediaFileEntity mediaFileEntity, com.github.kokorin.jaffree.ffprobe.Stream stream) {
        MediaFileStreamEntity.MediaFileStreamEntityBuilder<?, ?> mediaFileStream = MediaFileStreamEntity.builder()
                .mediaFileEntity(mediaFileEntity)
                .streamIndex(stream.getIndex())
                // ffprobe reports "unknown" for e.g. the rtp hint tracks in an iTunes m4v; Jaffree
                // then hands us null, and codec_name is NOT NULL in the database.
                .codecName(stream.getCodecName() != null ? stream.getCodecName() : "unknown")
                .codecType(codecTypeToEnum(stream.getCodecType().toString()))
                .language(stream.getTag("language"))
                .title(stream.getTag("title"))
                .path(mediaFileEntity.getPath());
        if (stream.getWidth() != null && stream.getHeight() != null) {
            mediaFileStream.width(stream.getWidth())
                    .height(stream.getHeight());
        }
        return mediaFileStream.build();
    }
}
