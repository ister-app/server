package app.ister.worker.events.mediafilefound;

import app.ister.core.MessageQueue;
import app.ister.core.entitiy.*;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.repository.*;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import app.ister.worker.events.Handle;
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
    private final MovieRepository movieRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;

    private final MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams;
    private final MediaFileFoundCreateBackground mediaFileFoundCreateBackground;
    private final MediaFileFoundGetDuration mediaFileFoundGetDuration;
    private final MessageSender messageSender;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    public HandleMediaFileFound(NodeService nodeService,
                                DirectoryRepository directoryRepository,
                                MediaFileRepository mediaFileRepository,
                                EpisodeRepository episodeRepository,
                                MovieRepository movieRepository,
                                MediaFileStreamRepository mediaFileStreamRepository,
                                MediaFileFoundCheckForStreams mediaFileFoundCheckForStreams,
                                MediaFileFoundCreateBackground mediaFileFoundCreateBackground,
                                MediaFileFoundGetDuration mediaFileFoundGetDuration,
                                MessageSender messageSender) {
        this.nodeService = nodeService;
        this.directoryRepository = directoryRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.episodeRepository = episodeRepository;
        this.movieRepository = movieRepository;
        this.mediaFileStreamRepository = mediaFileStreamRepository;
        this.mediaFileFoundCheckForStreams = mediaFileFoundCheckForStreams;
        this.mediaFileFoundCreateBackground = mediaFileFoundCreateBackground;
        this.mediaFileFoundGetDuration = mediaFileFoundGetDuration;
        this.messageSender = messageSender;
    }

    private static String getPathString(DirectoryEntity cacheDisk, Optional<EpisodeEntity> episodeEntity, Optional<MovieEntity> movieEntity) {
        String id = null;
        if (episodeEntity.isPresent()) {
            id = episodeEntity.get().getId().toString();
        } else if (movieEntity.isPresent()) {
            id = movieEntity.get().getId().toString();
        }
        return cacheDisk.getPath() + id + ".jpg";
    }

    @Override
    public EventType handles() {
        return EventType.MEDIA_FILE_FOUND;
    }

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_MEDIA_FILE_FOUND)
    @Override
    public void listener(app.ister.core.eventdata.MediaFileFoundData mediaFileFoundData) {
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
    public Boolean handle(app.ister.core.eventdata.MediaFileFoundData mediaFileFoundData) {
        DirectoryEntity directoryEntity = directoryRepository.findById(mediaFileFoundData.getDirectoryEntityUUID()).orElseThrow();
        Optional<EpisodeEntity> episodeEntity = mediaFileFoundData.getEpisodeEntityUUID() != null ? episodeRepository.findById(mediaFileFoundData.getEpisodeEntityUUID()) : Optional.empty();
        Optional<MovieEntity> movieEntity = mediaFileFoundData.getMovieEntityUUID() != null ? movieRepository.findById(mediaFileFoundData.getMovieEntityUUID()) : Optional.empty();
        var mediaFile = checkMediaFile(directoryEntity, mediaFileFoundData.getPath());
        mediaFile.ifPresent(mediaFileEntity -> createBackgroundImage(episodeEntity, movieEntity, mediaFileFoundData.getPath(), mediaFileEntity.getDurationInMilliseconds()));
        return true;
    }

    private Optional<MediaFileEntity> checkMediaFile(DirectoryEntity directoryEntity, String file) {
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, file);
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
     * Check if the given {@link EpisodeEntity} or {@link MovieEntity} has image entities if not:
     * Create background image for media file and save a reference to it in the database.
     */
    private void createBackgroundImage(Optional<EpisodeEntity> episodeEntity, Optional<MovieEntity> movieEntity, String mediaFilePath, long durationInMilliseconds) {
        if ((episodeEntity.isPresent() && episodeEntity.get().getImagesEntities().isEmpty()) ||
                (movieEntity.isPresent() && movieEntity.get().getImagesEntities().isEmpty())) {
            NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
            DirectoryEntity cacheDisk = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity).stream().findFirst().orElseThrow();
            String toPath = getPathString(cacheDisk, episodeEntity, movieEntity);
            mediaFileFoundCreateBackground.createBackground(Path.of(toPath), Path.of(mediaFilePath), dirOfFFmpeg, durationInMilliseconds / 2);

            ImageEntity imageEntity = ImageEntity.builder()
                    .directoryEntityId(cacheDisk.getId())
                    .path(toPath)
                    .sourceUri("file://" + mediaFilePath)
                    .type(ImageType.BACKGROUND)
                    .episodeEntity(episodeEntity.orElse(null))
                    .movieEntity(movieEntity.orElse(null))
                    .build();
            messageSender.sendImageFound(ImageFoundData.fromImageEntity(imageEntity));
        }
    }
}
