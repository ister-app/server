package app.ister.disk.events.imagefound;

import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.EventHandlingException;
import app.ister.core.Handle;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

@Service
@Transactional
public class HandleImageFound implements Handle<ImageFoundData> {
    private final ImageRepository imageRepository;

    public HandleImageFound(ImageRepository imageRepository) {
        this.imageRepository = imageRepository;
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
        try {
            BasicFileAttributes basicFileAttributes = Files.readAttributes(Path.of(messageData.getPath()), BasicFileAttributes.class);

            Optional<ImageEntity> oldImageEntity = imageRepository.findByDirectoryEntityIdAndPath(messageData.getDirectoryEntityId(), messageData.getPath());

            // The blur-hash is intentionally NOT computed here. Computing it (native AWT ImageIO +
            // BlurHash.encode per image) is CPU-heavy and made this handler the throughput bottleneck
            // for large batches (a full re-analyze queues tens of thousands of images). Just create
            // the row fast; the UPDATE_IMAGES_REQUESTED sweep fills in blur-hashes afterwards. An
            // existing blur-hash is left untouched.
            ImageEntity imageEntity;
            if (oldImageEntity.isPresent()) {
                imageEntity = oldImageEntity.get();
                imageEntity.setFileLastModifiedTime(basicFileAttributes.lastModifiedTime().toInstant());
                imageEntity.setFileCreationTime(basicFileAttributes.creationTime().toInstant());
                imageEntity.setShowEntityId(messageData.getShowEntityId());
                imageEntity.setMovieEntityId(messageData.getMovieEntityId());
                imageEntity.setEpisodeEntityId(messageData.getEpisodeEntityId());
                imageEntity.setSeasonEntityId(messageData.getSeasonEntityId());
                imageEntity.setPersonEntityId(messageData.getPersonEntityId());
                imageEntity.setAlbumEntityId(messageData.getAlbumEntityId());
                imageEntity.setBookEntityId(messageData.getBookEntityId());
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
                        .podcastEntityId(messageData.getPodcastEntityId())
                        .fileLastModifiedTime(basicFileAttributes.lastModifiedTime().toInstant())
                        .fileCreationTime(basicFileAttributes.creationTime().toInstant())
                        .build();
            }
            imageRepository.save(imageEntity);
        } catch (IOException e) {
            throw new EventHandlingException("Failed to process image at " + messageData.getPath(), e);
        }
    }
}
