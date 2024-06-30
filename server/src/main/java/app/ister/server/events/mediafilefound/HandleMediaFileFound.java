package app.ister.server.events.mediafilefound;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.EventType;
import app.ister.server.enums.ImageType;
import app.ister.server.events.Handle;
import app.ister.server.events.MessageQueue;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.ImageRepository;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import app.ister.server.service.NodeService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Optional;

@Service
@Transactional
public class HandleMediaFileFound implements Handle<MediaFileFoundData> {
    private final NodeService nodeService;
    private final DirectoryRepository directoryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final EpisodeRepository episodeRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final ImageRepository imageRepository;

    private final MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams;
    private final MediaFileFoundCreateBackground mediaFileFoundCreateBackground;
    private final MediaFileFoundGetDuration mediaFileFoundGetDuration;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    public HandleMediaFileFound(NodeService nodeService,
                                DirectoryRepository directoryRepository,
                                MediaFileRepository mediaFileRepository, EpisodeRepository episodeRepository,
                                MediaFileStreamRepository mediaFileStreamRepository,
                                ImageRepository imageRepository,
                                MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams,
                                MediaFileFoundCreateBackground mediaFileFoundCreateBackground,
                                MediaFileFoundGetDuration mediaFileFoundGetDuration) {
        this.nodeService = nodeService;
        this.directoryRepository = directoryRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.episodeRepository = episodeRepository;
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

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_MEDIA_FILE_FOUND)
    @Override
    public void listener(MediaFileFoundData mediaFileFoundData) {
        Handle.super.listener(mediaFileFoundData);
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
    public Boolean handle(MediaFileFoundData mediaFileFoundData) {
        DirectoryEntity directoryEntity = directoryRepository.findById(mediaFileFoundData.getDirectoryEntityUUID()).orElseThrow();
        EpisodeEntity episodeEntity = episodeRepository.findById(mediaFileFoundData.getEpisodeEntityUUID()).orElseThrow();
        var mediaFile = checkMediaFile(directoryEntity, episodeEntity, mediaFileFoundData.getPath());
        mediaFile.ifPresent(mediaFileEntity -> createBackgroundImage(episodeEntity, mediaFileFoundData.getPath(), mediaFileEntity.getDurationInMilliseconds()));
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
        if (episode.getImagesEntities().isEmpty()) {
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
