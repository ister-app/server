package app.ister.server.eventHandlers;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.EventType;
import app.ister.server.enums.ImageType;
import app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundCheckForStreams;
import app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundCreateBackground;
import app.ister.server.eventHandlers.mediaFileFound.MediaFileFoundGetDuration;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.ImageRepository;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import app.ister.server.service.NodeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

@Component
public class HandleMediaFileFound implements Handle {
    private final NodeService nodeService;
    private final DirectoryRepository directoryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final ImageRepository imageRepository;

    private final MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams;
    private final MediaFileFoundCreateBackground mediaFileFoundCreateBackground;
    private final MediaFileFoundGetDuration mediaFileFoundGetDuration;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    public HandleMediaFileFound(NodeService nodeService,
                                DirectoryRepository directoryRepository,
                                MediaFileRepository mediaFileRepository,
                                MediaFileStreamRepository mediaFileStreamRepository,
                                ImageRepository imageRepository,
                                MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams,
                                MediaFileFoundCreateBackground mediaFileFoundCreateBackground,
                                MediaFileFoundGetDuration mediaFileFoundGetDuration) {
        this.nodeService = nodeService;
        this.directoryRepository = directoryRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.mediaFileStreamRepository = mediaFileStreamRepository;
        this.imageRepository = imageRepository;
        this.mediaFileFoundCheckForStreams = mediaFileFoundCheckForStreams;
        this.mediaFileFoundCreateBackground = mediaFileFoundCreateBackground;
        this.mediaFileFoundGetDuration = mediaFileFoundGetDuration;
    }

    @Override
    public EventType handles() {
        return EventType.MEDIA_FILE_FOUND;
    }

    /**
     * When the scanner find the media file it saves the data in the database.
     * The scanner is not analyzing the media file, because it can take a bit longer.
     * So this handler will analyze the media file.
     * - The duration of the file.
     * - And the containing streams (video, audio and subtitles streams).
     * - And will create a background image.
     */
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
            mediaFileEntity.setDurationInMilliseconds(mediaFileFoundGetDuration.getDuration(mediaFileEntity.getPath()));
            mediaFileRepository.save(mediaFileEntity);

            // Analyze media file streams and save the metadata.
            mediaFileStreamRepository.saveAll(mediaFileFoundCheckForStreams.checkForStreams(mediaFileEntity, dirOfFFmpeg));
        });
        return mediaFile;
    }

    /**
     * Create background image for media file and save a reference to it in the database.
     */
    private void createBackgroundImage(EpisodeEntity episode, String mediaFilePath, Long durationInMilliseconds) {
        if (episode.getImagesEntities().size() == 0) {
            NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
            DirectoryEntity cacheDisk = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity).stream().findFirst().orElseThrow();
            String toPath = cacheDisk.getPath() + episode.getId() + ".jpg";
            mediaFileFoundCreateBackground.createBackground(Path.of(toPath), Path.of(mediaFilePath), dirOfFFmpeg, durationInMilliseconds / 2);

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
}
