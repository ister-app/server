package app.ister.server.scanner;

import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

@Component
@Slf4j
public class MediaFileAnalyzer {

    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;

    public void checkMediaFile(DiskEntity diskEntity, EpisodeEntity isEpisode, Path file, BasicFileAttributes attrs) {
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDiskEntityAndEpisodeEntityAndPath(diskEntity, isEpisode, file.toString());
        if (mediaFile.isEmpty()) {
            MediaFileEntity entity = new MediaFileEntity(diskEntity, isEpisode, file.toString(), attrs.size());
            mediaFileRepository.save(entity);
            checkMediaFileForStreams(entity);
        }
    }

    private void checkMediaFileForStreams(MediaFileEntity mediaFileEntity) {
        FFprobeResult result = FFprobe.atPath()
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
