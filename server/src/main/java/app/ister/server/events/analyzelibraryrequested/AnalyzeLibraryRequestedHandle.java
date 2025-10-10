package app.ister.server.events.analyzelibraryrequested;

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
import java.util.ArrayList;

@Service
@Slf4j
@Transactional
public class AnalyzeLibraryRequestedHandle implements Handle<AnalyzeLibraryRequestedData> {

    private final ImageRepository imageRepository;

    public AnalyzeLibraryRequestedHandle(ImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED)
    @Override
    public void listener(AnalyzeLibraryRequestedData analyzeLibraryRequestedData) {
        Handle.super.listener(analyzeLibraryRequestedData);
    }

    @Override
    public EventType handles() {
        return EventType.ANALYZE_LIBRARY_REQUEST;
    }

    @Override
    public Boolean handle(AnalyzeLibraryRequestedData messageData) {

        ArrayList<ImageEntity> updatedImages = new ArrayList<>();

        imageRepository.findAll().forEach(image -> {
            try {
                BasicFileAttributes basicFileAttributes = Files.readAttributes(Path.of(image.getPath()), BasicFileAttributes.class);
                if (image.getFileLastModifiedTime() == null || basicFileAttributes.lastModifiedTime().toInstant().toEpochMilli() != image.getFileLastModifiedTime().toEpochMilli()) {
                    log.debug("Anlyzing image: " + image.getPath());
                    File file = new File(image.getPath());
                    BufferedImage bufferedImage = ImageIO.read(file);
                    String blurHash = BlurHash.encode(bufferedImage);

                    image.setBlurHash(blurHash);
                    image.setFileLastModifiedTime(basicFileAttributes.lastModifiedTime().toInstant());
                    image.setFileCreationTime(basicFileAttributes.creationTime().toInstant());
                    updatedImages.add(image);
                }
            } catch (IOException e) {
                log.error(e.getMessage());
            }

        });
        imageRepository.saveAll(updatedImages);
        return true;
    }
}
