package app.ister.server.eventHandlers;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.ImageType;
import app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundGetDuration;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.ImageRepository;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import app.ister.server.service.NodeService;
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
    private NodeService nodeService;
    @Autowired
    private DirectoryRepository directoryRepository;
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
        var mediaFile = checkMediaFile(serverEventEntity.getDirectoryEntity(), serverEventEntity.getEpisodeEntity(), serverEventEntity.getPath());
        mediaFile.ifPresent(mediaFileEntity -> createBackgroundImage(serverEventEntity.getEpisodeEntity(), serverEventEntity.getPath(), mediaFileEntity.getDurationInMilliseconds()));
        return true;
    }

    private Optional<MediaFileEntity> checkMediaFile(DirectoryEntity directoryEntity, EpisodeEntity episode, String file) {
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndEpisodeEntityAndPath(directoryEntity, episode, file);
        mediaFile.ifPresent(mediaFileEntity -> {
            // Get duration.
            mediaFileEntity.setDurationInMilliseconds(MediaFileFoundGetDuration.getDuration(mediaFileEntity.getPath(), dirOfFFmpeg));
            mediaFileRepository.save(mediaFileEntity);

            // Analyze media file streams and save the metadata.
            mediaFileStreamRepository.saveAll(checkForStreams(mediaFileEntity, dirOfFFmpeg));
        });
        return mediaFile;
    }

    /**
     * Create background image for media file and save a reference to it in the database.
     */
    private void createBackgroundImage(EpisodeEntity episode, String mediaFilePath, Long durationInMilliseconds) {
        NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
        var cacheDisk = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity).stream().findFirst().orElseThrow();
        String toPath = cacheDisk.getPath() + episode.getId() + ".jpg";
        createBackground(Path.of(toPath), Path.of(mediaFilePath), dirOfFFmpeg, durationInMilliseconds / 2);

        ImageEntity imageEntity = ImageEntity.builder()
                .directoryEntity(cacheDisk)
                .path(toPath)
                .sourceUri("file://" + mediaFilePath)
                .type(ImageType.BACKGROUND)
                .episodeEntity(episode)
                .build();

        imageRepository.save(imageEntity);
    }
}
