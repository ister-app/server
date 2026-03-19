package app.ister.disk.events.imagefound;

import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.ImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleImageFoundTest {

    @InjectMocks
    private HandleImageFound subject;

    @Mock
    private ImageRepository imageRepository;

    @TempDir
    Path tempDir;

    @Test
    void handles() {
        assertEquals(EventType.IMAGE_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        ImageFoundData data = ImageFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleSavesNewImageEntity() throws IOException {
        Path imageFile = tempDir.resolve("test.png");
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", imageFile.toFile());

        UUID directoryId = UUID.randomUUID();
        ImageFoundData data = ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .path(imageFile.toString())
                .directoryEntityId(directoryId)
                .imageType(ImageType.BACKGROUND)
                .build();

        when(imageRepository.findByDirectoryEntityIdAndPath(directoryId, imageFile.toString()))
                .thenReturn(Optional.empty());

        boolean result = subject.handle(data);

        assertTrue(result);
        verify(imageRepository).save(any(ImageEntity.class));
    }

    @Test
    void handleUpdatesExistingImageEntity() throws IOException {
        Path imageFile = tempDir.resolve("test.png");
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", imageFile.toFile());

        UUID directoryId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        ImageEntity existing = ImageEntity.builder().id(existingId).build();
        ImageFoundData data = ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .path(imageFile.toString())
                .directoryEntityId(directoryId)
                .imageType(ImageType.COVER)
                .build();

        when(imageRepository.findByDirectoryEntityIdAndPath(directoryId, imageFile.toString()))
                .thenReturn(Optional.of(existing));

        boolean result = subject.handle(data);

        assertTrue(result);
        verify(imageRepository).save(any(ImageEntity.class));
    }

    @Test
    void handleReturnsFalseWhenFileDoesNotExist() {
        ImageFoundData data = ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .path("/nonexistent/path/image.jpg")
                .directoryEntityId(UUID.randomUUID())
                .build();

        boolean result = subject.handle(data);

        assertFalse(result);
        verify(imageRepository, never()).save(any());
    }
}
