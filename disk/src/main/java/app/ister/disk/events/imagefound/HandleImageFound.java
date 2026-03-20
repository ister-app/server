package app.ister.disk.events.imagefound;

import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.ImageRepository;
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
@Slf4j
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
    public Boolean handle(app.ister.core.eventdata.ImageFoundData messageData) {
        try {
            BasicFileAttributes basicFileAttributes = Files.readAttributes(Path.of(messageData.getPath()), BasicFileAttributes.class);

            File file = new File(messageData.getPath());
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                log.error("Failed to read image at {}: unsupported format", messageData.getPath());
                return false;
            }
            String blurHash = BlurHash.encode(image);

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
                        .fileLastModifiedTime(basicFileAttributes.lastModifiedTime().toInstant())
                        .fileCreationTime(basicFileAttributes.creationTime().toInstant())
                        .build();
            }
            imageRepository.save(imageEntity);
            return true;
        } catch (IOException e) {
            log.error("Failed to process image at {}: {}", messageData.getPath(), e.getMessage(), e);
            return false;
        }

    }
}
