package app.ister.server.scanner.analyzers;

import app.ister.server.entitiy.*;
import app.ister.server.enums.ImageType;
import app.ister.server.enums.StreamCodecType;
import app.ister.server.repository.ImageRepository;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Component
@Slf4j
public class MediaFileAnalyzer {
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;
    @Autowired
    private ImageRepository imageRepository;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    public void checkMediaFile(DiskEntity diskEntity, EpisodeEntity episode, String file) {
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDiskEntityAndEpisodeEntityAndPath(diskEntity, episode, file);
        mediaFile.ifPresent(this::checkMediaFileForStreams);
    }

    public void createBackground(DiskEntity diskEntity, EpisodeEntity episodeEntity, String toPath, String mediaFile) {
        FFmpeg.atPath(Path.of(dirOfFFmpeg))
                .addInput(
                        UrlInput.fromUrl(mediaFile)
                )
                .addOutput(
                        UrlOutput.toPath(Path.of(toPath))
                                .addArguments("-ss", "00:01:00")
                                .addArguments("-vf", "scale='trunc(ih*dar):ih',setsar=1/1")
                                .addArguments("-frames:v", "1")
                                .addArguments("-q:v", "2")
                )
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
        imageRepository.save(ImageEntity.builder()
                .diskEntity(diskEntity)
                .path(toPath)
                .type(ImageType.BACKGROUND)
                .episodeEntity(episodeEntity)
                .build());
    }

    private void checkMediaFileForStreams(MediaFileEntity mediaFileEntity) {
        FFprobeResult result = FFprobe.atPath(Paths.get(dirOfFFmpeg))
                .setShowStreams(true)
//                .setShowChapters(true)
//                .setShowData(true)
                .setInput(mediaFileEntity.getPath())
                .setLogLevel(LogLevel.ERROR)
                .execute();


        for (com.github.kokorin.jaffree.ffprobe.Stream stream : result.getStreams()) {

            var mediaFileStream = MediaFileStreamEntity.builder()
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
            mediaFileStreamRepository.save(mediaFileStream.build());
        }
    }

    private StreamCodecType codecTypeToEnum(String codecType) {
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
}
