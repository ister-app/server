package app.ister.server.scanner;

import app.ister.server.entitiy.*;
import app.ister.server.enums.ImageType;
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
        if (mediaFile.isEmpty()) {
            MediaFileEntity entity = new MediaFileEntity(diskEntity, episode, file, 0);
            mediaFileRepository.save(entity);
            checkMediaFileForStreams(entity);
        }
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
            var mediaFileStream = new MediaFileStreamEntity();
            mediaFileStream.setMediaFileEntity(mediaFileEntity);
            mediaFileStream.setIndex(stream.getIndex());
            mediaFileStream.setCodecName(stream.getCodecName());
            mediaFileStream.setCodecType(stream.getCodecType().toString());
            if (stream.getWidth() != null && stream.getHeight() != null) {
                mediaFileStream.setWidth(stream.getWidth());
                mediaFileStream.setHeight(stream.getHeight());
            }
            mediaFileStream.setLanguage(stream.getTag("language"));
            mediaFileStream.setTitle(stream.getTag("title"));
            mediaFileStreamRepository.save(mediaFileStream);
        }
    }
}
