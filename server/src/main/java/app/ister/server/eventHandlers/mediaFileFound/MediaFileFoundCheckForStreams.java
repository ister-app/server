package app.ister.server.eventHandlers.mediaFileFound;

import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.enums.StreamCodecType;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class MediaFileFoundCheckForStreams {
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

    public List<MediaFileStreamEntity> checkForStreams(MediaFileEntity mediaFileEntity, String dirOfFFmpeg) {
        List<MediaFileStreamEntity> result = new ArrayList<>();
        FFprobeResult mediaStreams = FFprobe.atPath(Paths.get(dirOfFFmpeg))
                .setShowStreams(true)
                .setInput(mediaFileEntity.getPath())
                .setLogLevel(LogLevel.ERROR)
                .execute();

        for (com.github.kokorin.jaffree.ffprobe.Stream stream : mediaStreams.getStreams()) {
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
        return result;
    }
}
