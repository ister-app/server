package app.ister.server.events.imagefound;

import app.ister.server.entitiy.ImageEntity;
import app.ister.server.enums.EventType;
import app.ister.server.events.Handle;
import app.ister.server.events.MessageQueue;
import app.ister.server.repository.ImageRepository;
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

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_IMAGE_FOUND)
    @Override
    public void listener(ImageFoundData imageFoundData) {
        Handle.super.listener(imageFoundData);
    }

    @Override
    public Boolean handle(ImageFoundData messageData) {
        try {
            BasicFileAttributes basicFileAttributes = Files.readAttributes(Path.of(messageData.getPath()), BasicFileAttributes.class);

            File file = new File(messageData.getPath());
            BufferedImage image = ImageIO.read(file);
            String blurHash = BlurHash.encode(image);

            ImageEntity imageEntity = ImageEntity.builder()
                    .directoryEntityId(messageData.getDirectoryEntityId())
                    .blurHash(blurHash)
                    .path(messageData.getPath())
                    .sourceUri(messageData.getSourceUri())
                    .type(messageData.getImageType())
                    .episodeEntityId(messageData.getEpisodeEntityId())
                    .movieEntityId(messageData.getMovieEntityId())
                    .showEntityId(messageData.getShowEntityId())
                    .fileLastModifiedTime(basicFileAttributes.lastModifiedTime().toInstant())
                    .fileCreationTime(basicFileAttributes.creationTime().toInstant())
                    .build();
            imageRepository.save(imageEntity);
            return true;
        } catch (IOException e) {
            log.error(e.getMessage());
            return false;
        }

    }
}
