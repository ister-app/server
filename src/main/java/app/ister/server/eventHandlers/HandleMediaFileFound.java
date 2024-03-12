package app.ister.server.eventHandlers;

import app.ister.server.entitiy.*;
import app.ister.server.enums.DiskType;
import app.ister.server.enums.ImageType;
import app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundGetDuration;
import app.ister.server.repository.DiskRepository;
import app.ister.server.repository.ImageRepository;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

import static app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundCheckForStreams.checkForStreams;
import static app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundCreateBackground.createBackground;

@Component
public class HandleMediaFileFound implements Handle {
    @Autowired
    private DiskRepository diskRepository;
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;
    @Autowired
    private ImageRepository imageRepository;
    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    @Override
    public Boolean handle(ServerEventEntity serverEventEntity) {
        var mediaFile = checkMediaFile(serverEventEntity.getDiskEntity(), serverEventEntity.getEpisodeEntity(), serverEventEntity.getPath());
        mediaFile.ifPresent(mediaFileEntity -> createBackgroundImage(serverEventEntity.getEpisodeEntity(), serverEventEntity.getPath(), mediaFileEntity.getDurationInMilliseconds()));
        return true;
    }

    private Optional<MediaFileEntity> checkMediaFile(DiskEntity diskEntity, EpisodeEntity episode, String file) {
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDiskEntityAndEpisodeEntityAndPath(diskEntity, episode, file);
        mediaFile.ifPresent(mediaFileEntity -> {
            // Get duration.
            mediaFileEntity.setDurationInMilliseconds(MediaFileFoundGetDuration.getDuration(mediaFileEntity.getPath(), dirOfFFmpeg));
            mediaFileRepository.save(mediaFileEntity);

            // Analyze media file streams and save the metadata.
            checkForStreams(mediaFileEntity, dirOfFFmpeg).forEach(mediaFileStreamEntity -> mediaFileStreamRepository.save(mediaFileStreamEntity));
        });
        return mediaFile;
    }

    /**
     * Create background image for mediaFile and save a reference to it in the database.
     */
    private void createBackgroundImage(EpisodeEntity episode, String mediaFilePath, Long durationInMilliseconds) {
        var cacheDisk = diskRepository.findByDiskType(DiskType.CACHE).stream().findFirst().orElseThrow();
        String toPath = cacheDisk.getPath() + episode.getId() + ".jpg";
        createBackground(Path.of(toPath), Path.of(mediaFilePath), dirOfFFmpeg, durationInMilliseconds / 2);

        ImageEntity imageEntity = ImageEntity.builder()
                .diskEntity(cacheDisk)
                .path(toPath)
                .type(ImageType.BACKGROUND)
                .episodeEntity(episode)
                .build();

        imageRepository.save(imageEntity);
    }
}
