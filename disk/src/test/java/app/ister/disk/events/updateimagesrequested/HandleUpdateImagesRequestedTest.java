package app.ister.disk.events.updateimagesrequested;

import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.UpdateImagesRequestedData;
import app.ister.core.repository.ImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleUpdateImagesRequestedTest {

    @InjectMocks
    private HandleUpdateImagesRequested subject;

    @Mock
    private ImageRepository imageRepository;

    @Test
    void handles() {
        assertEquals(EventType.UPDATE_IMAGES_REQUESTED, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        UpdateImagesRequestedData data = UpdateImagesRequestedData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleSkipsImagesWithExistingBlurHash() {
        ImageEntity imageWithHash = ImageEntity.builder().blurHash("existing-hash").build();
        UpdateImagesRequestedData data = UpdateImagesRequestedData.builder()
                .eventType(EventType.UPDATE_IMAGES_REQUESTED)
                .build();

        when(imageRepository.findAll()).thenReturn(List.of(imageWithHash));

        assertTrue(subject.handle(data));

        verify(imageRepository, never()).saveAll(any());
    }

    @Test
    void handleSkipsImageWhenFileDoesNotExist() {
        ImageEntity imageWithoutHash = ImageEntity.builder()
                .path("/nonexistent/path/image.jpg")
                .build();
        UpdateImagesRequestedData data = UpdateImagesRequestedData.builder()
                .eventType(EventType.UPDATE_IMAGES_REQUESTED)
                .build();

        when(imageRepository.findAll()).thenReturn(List.of(imageWithoutHash));

        assertTrue(subject.handle(data));

        verify(imageRepository, never()).saveAll(any());
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        UpdateImagesRequestedData data = UpdateImagesRequestedData.builder()
                .eventType(EventType.UPDATE_IMAGES_REQUESTED)
                .build();
        when(imageRepository.findAll()).thenReturn(List.of());
        assertDoesNotThrow(() -> subject.listener(data));
    }
}
