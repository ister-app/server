package app.ister.disk.events.updateimagesrequested;

import app.ister.core.Handle;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.UpdateImagesRequestedData;
import app.ister.core.repository.ImageRepository;
import io.trbl.blurhash.BlurHash;
import lombok.RequiredArgsConstructor;
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
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class HandleUpdateImagesRequested implements Handle<UpdateImagesRequestedData> {

    private final ImageRepository imageRepository;

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getUpdateImagesRequestedQueues()}")
    @Override
    public void listener(UpdateImagesRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public EventType handles() {
        return EventType.UPDATE_IMAGES_REQUESTED;
    }

    @Override
    public Boolean handle(UpdateImagesRequestedData data) {
        List<ImageEntity> toUpdate = new ArrayList<>();
        imageRepository.findAll().forEach(imageEntity -> {
            if (imageEntity.getBlurHash() == null && applyBlurHash(imageEntity)) {
                toUpdate.add(imageEntity);
            }
        });
        if (!toUpdate.isEmpty()) {
            imageRepository.saveAll(toUpdate);
        }
        return true;
    }

    private boolean applyBlurHash(ImageEntity imageEntity) {
        try {
            BufferedImage bi = ImageIO.read(new File(imageEntity.getPath()));
            String blurHash = BlurHash.encode(bi);

            BasicFileAttributes attrs = Files.readAttributes(
                    Path.of(imageEntity.getPath()), BasicFileAttributes.class);

            imageEntity.setBlurHash(blurHash);
            imageEntity.setFileLastModifiedTime(attrs.lastModifiedTime().toInstant());
            imageEntity.setFileCreationTime(attrs.creationTime().toInstant());

            log.debug("Updated blur-hash for {}", imageEntity.getPath());
            return true;
        } catch (IOException e) {
            log.error("Unable to process imageEntity {}: {}", imageEntity.getPath(), e.getMessage());
            return false;
        }
    }
}
