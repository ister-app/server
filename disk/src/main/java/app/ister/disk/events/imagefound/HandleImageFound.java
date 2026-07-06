package app.ister.disk.events.imagefound;

import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.EventHandlingException;
import app.ister.core.Handle;
import io.trbl.blurhash.BlurHash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

@Service
@Transactional
@Slf4j
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

            // The blur-hash is a best-effort placeholder. Computing it goes through native AWT
            // (ImageIO / colour management) which can fail for some files in the native image; a
            // failure must NOT stop the image row from being created, otherwise the image never
            // shows up at all. Store a null blur-hash instead and let the image display.
            String blurHash = computeBlurHashOrNull(messageData.getPath());

            Optional<ImageEntity> oldImageEntity = imageRepository.findByDirectoryEntityIdAndPath(messageData.getDirectoryEntityId(), messageData.getPath());

            ImageEntity imageEntity;
            if (oldImageEntity.isPresent()) {
                imageEntity = oldImageEntity.get();
                imageEntity.setBlurHash(blurHash);
                imageEntity.setFileLastModifiedTime(basicFileAttributes.lastModifiedTime().toInstant());
                imageEntity.setFileCreationTime(basicFileAttributes.creationTime().toInstant());
                imageEntity.setShowEntityId(messageData.getShowEntityId());
                imageEntity.setMovieEntityId(messageData.getMovieEntityId());
                imageEntity.setEpisodeEntityId(messageData.getEpisodeEntityId());
                imageEntity.setSeasonEntityId(messageData.getSeasonEntityId());
                imageEntity.setPersonEntityId(messageData.getPersonEntityId());
                imageEntity.setAlbumEntityId(messageData.getAlbumEntityId());
            } else {
                imageEntity = ImageEntity.builder()
                        .directoryEntityId(messageData.getDirectoryEntityId())
                        .blurHash(blurHash)
                        .path(messageData.getPath())
                        .sourceUri(messageData.getSourceUri())
                        .type(messageData.getImageType())
                        .episodeEntityId(messageData.getEpisodeEntityId())
                        .movieEntityId(messageData.getMovieEntityId())
                        .showEntityId(messageData.getShowEntityId())
                        .seasonEntityId(messageData.getSeasonEntityId())
                        .personEntityId(messageData.getPersonEntityId())
                        .albumEntityId(messageData.getAlbumEntityId())
                        .fileLastModifiedTime(basicFileAttributes.lastModifiedTime().toInstant())
                        .fileCreationTime(basicFileAttributes.creationTime().toInstant())
                        .build();
            }
            imageRepository.save(imageEntity);
        } catch (IOException e) {
            throw new EventHandlingException("Failed to process image at " + messageData.getPath(), e);
        }
    }

    private String computeBlurHashOrNull(String path) {
        try {
            BufferedImage image = ImageIO.read(new File(path));
            if (image == null) {
                log.warn("Unsupported image format, storing without blur-hash: {}", path);
                return null;
            }
            return BlurHash.encode(image);
        } catch (IOException | RuntimeException | LinkageError e) {
            log.warn("Could not compute blur-hash for {}: {}", path, e.getMessage());
            return null;
        }
    }
}
