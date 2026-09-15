package app.ister.disk.events.imagefound;

import app.ister.core.status.ActivityContext;
import app.ister.core.status.ActivitySubjects;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.storage.FileAccess;
import app.ister.core.storage.ObjectStat;
import java.time.Instant;
import app.ister.core.EventHandlingException;
import app.ister.core.Handle;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Optional;

@Service
@Transactional
public class HandleImageFound implements Handle<ImageFoundData> {
    private final ImageRepository imageRepository;
    private final DirectoryRepository directoryRepository;
    private final FileAccess fileAccess;

    public HandleImageFound(ImageRepository imageRepository, DirectoryRepository directoryRepository, FileAccess fileAccess) {
        this.imageRepository = imageRepository;
        this.directoryRepository = directoryRepository;
        this.fileAccess = fileAccess;
    }

    @Override
    public EventType handles() {
        return EventType.IMAGE_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getImageFoundQueues()}")
    @Override
    public void listener(app.ister.core.eventdata.ImageFoundData imageFoundData) {
        Handle.super.listener(imageFoundData);
    }

    @Override
    public void handle(app.ister.core.eventdata.ImageFoundData messageData) {
        ActivityContext.subject(ActivitySubjects.fileName(messageData.getPath()));
        try {
            DirectoryEntity directory = directoryRepository.findById(messageData.getDirectoryEntityId()).orElseThrow();
            ObjectStat stat = fileAccess.stat(directory, messageData.getPath())
                    .orElseThrow(() -> new java.nio.file.NoSuchFileException(messageData.getPath()));
            Instant lastModified = stat.lastModified();
            Instant created = FileAccess.creationTime(messageData.getPath(), stat);

            Optional<ImageEntity> oldImageEntity = imageRepository.findByDirectoryEntityIdAndPath(messageData.getDirectoryEntityId(), messageData.getPath());

            // The blur-hash is intentionally NOT computed here. Computing it (native AWT ImageIO +
            // BlurHash.encode per image) is CPU-heavy and made this handler the throughput bottleneck
            // for large batches (a full re-analyze queues tens of thousands of images). Just create
            // the row fast; the UPDATE_IMAGES_REQUESTED sweep fills in blur-hashes afterwards. An
            // existing blur-hash is left untouched.
            ImageEntity imageEntity;
            if (oldImageEntity.isPresent()) {
                imageEntity = oldImageEntity.get();
                imageEntity.setFileLastModifiedTime(lastModified);
                imageEntity.setFileCreationTime(created);
                imageEntity.setShowEntityId(messageData.getShowEntityId());
                imageEntity.setMovieEntityId(messageData.getMovieEntityId());
                imageEntity.setEpisodeEntityId(messageData.getEpisodeEntityId());
                imageEntity.setSeasonEntityId(messageData.getSeasonEntityId());
                imageEntity.setPersonEntityId(messageData.getPersonEntityId());
                imageEntity.setAlbumEntityId(messageData.getAlbumEntityId());
                imageEntity.setBookEntityId(messageData.getBookEntityId());
                imageEntity.setSeriesEntityId(messageData.getSeriesEntityId());
                imageEntity.setPodcastEntityId(messageData.getPodcastEntityId());
            } else {
                imageEntity = ImageEntity.builder()
                        .directoryEntityId(messageData.getDirectoryEntityId())
                        .path(messageData.getPath())
                        .sourceUri(messageData.getSourceUri())
                        .type(messageData.getImageType())
                        .episodeEntityId(messageData.getEpisodeEntityId())
                        .movieEntityId(messageData.getMovieEntityId())
                        .showEntityId(messageData.getShowEntityId())
                        .seasonEntityId(messageData.getSeasonEntityId())
                        .personEntityId(messageData.getPersonEntityId())
                        .albumEntityId(messageData.getAlbumEntityId())
                        .bookEntityId(messageData.getBookEntityId())
                        .seriesEntityId(messageData.getSeriesEntityId())
                        .podcastEntityId(messageData.getPodcastEntityId())
                        .fileLastModifiedTime(lastModified)
                        .fileCreationTime(created)
                        .build();
            }
            imageRepository.save(imageEntity);
        } catch (IOException e) {
            throw new EventHandlingException("Failed to process image at " + messageData.getPath(), e);
        }
    }
}
