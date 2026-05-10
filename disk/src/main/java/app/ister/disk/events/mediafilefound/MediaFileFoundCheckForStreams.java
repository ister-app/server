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

    public CheckResult checkForStreams(MediaFileEntity mediaFileEntity, String dirOfFFmpeg) {
        List<MediaFileStreamEntity> result = new ArrayList<>();
        List<Long> durationList = new ArrayList<>();
        boolean hasAttachedPic = false;
        FFprobeResult mediaStreams = FFprobe.atPath(Paths.get(dirOfFFmpeg))
                .setShowStreams(true)
                .setInput(mediaFileEntity.getPath())
                .setLogLevel(LogLevel.ERROR)
                .execute();

        for (com.github.kokorin.jaffree.ffprobe.Stream stream : mediaStreams.getStreams()) {
            if (stream.getDuration() != null) {
                durationList.add(Math.round(stream.getDuration().doubleValue() * 1000));
            }
            for (String tag : DURATION_TAGS) {
                String tagValue = stream.getTag(tag);
                if (tagValue != null) {
                    durationList.add(LocalTime.parse(tagValue).getLong(ChronoField.MILLI_OF_DAY));
                }
            }

            if (stream.getDisposition() != null
                    && Boolean.TRUE.equals(stream.getDisposition().getAttachedPic())) {
                hasAttachedPic = true;
                continue;
            }
            MediaFileStreamEntity.MediaFileStreamEntityBuilder<?, ?> mediaFileStream = MediaFileStreamEntity.builder()
                    .mediaFileEntity(mediaFileEntity)
                    .streamIndex(stream.getIndex())
                    .codecName(stream.getCodecName())
                    .codecType(codecTypeToEnum(stream.getCodecType().toString()))
                    .language(stream.getTag("language"))
                    .title(stream.getTag("title"))
                    .path(mediaFileEntity.getPath());
            if (stream.getWidth() != null && stream.getHeight() != null) {
                mediaFileStream.width(stream.getWidth())
                        .height(stream.getHeight());
            }
            result.add(mediaFileStream.build());
        }
        long duration = durationList.isEmpty() ? 0L : Collections.max(durationList);
        return new CheckResult(result, hasAttachedPic, duration);
    }
}
