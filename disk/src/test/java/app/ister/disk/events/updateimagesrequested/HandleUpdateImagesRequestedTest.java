package app.ister.disk.events.updateimagesrequested;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.UpdateImagesRequestedData;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleUpdateImagesRequestedTest {

    private static final int CHUNK_SIZE = 2;
    private static final UUID DIRECTORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIRECTORY_NAME = "cache-directory";

    @InjectMocks
    private HandleUpdateImagesRequested subject;

    @Mock
    private BlurHashChunkProcessor chunkProcessor;

    @Mock
    private MessageSender messageSender;

    @BeforeEach
    void setChunkSize() {
        ReflectionTestUtils.setField(subject, "chunkSize", CHUNK_SIZE);
    }

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
    void listenerCallsHandleWithCorrectEventType() {
        when(chunkProcessor.process(any(), any(), anyInt())).thenReturn(new BlurHashChunkProcessor.Chunk(0, null));
        assertDoesNotThrow(() -> subject.listener(request(null)));
    }

    @Test
    void fullChunkContinuesTheSweepAfterTheLastProcessedImage() {
        UUID lastId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(chunkProcessor.process(DIRECTORY_ID, null, CHUNK_SIZE))
                .thenReturn(new BlurHashChunkProcessor.Chunk(CHUNK_SIZE, lastId));

        subject.handle(request(null));

        ArgumentCaptor<UpdateImagesRequestedData> successor = ArgumentCaptor.forClass(UpdateImagesRequestedData.class);
        verify(messageSender).sendUpdateImagesRequested(successor.capture(), eq(DIRECTORY_NAME));
        assertEquals(EventType.UPDATE_IMAGES_REQUESTED, successor.getValue().getEventType());
        assertEquals(DIRECTORY_ID, successor.getValue().getDirectoryEntityId());
        assertEquals(DIRECTORY_NAME, successor.getValue().getDirectoryName());
        assertEquals(lastId, successor.getValue().getAfterId());
    }

    @Test
    void sweepResumesFromTheCursorOfTheIncomingMessage() {
        UUID cursor = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(chunkProcessor.process(DIRECTORY_ID, cursor, CHUNK_SIZE))
                .thenReturn(new BlurHashChunkProcessor.Chunk(0, null));

        subject.handle(request(cursor));

        verify(chunkProcessor).process(DIRECTORY_ID, cursor, CHUNK_SIZE);
    }

    @Test
    void emptyChunkEndsTheSweep() {
        when(chunkProcessor.process(DIRECTORY_ID, null, CHUNK_SIZE))
                .thenReturn(new BlurHashChunkProcessor.Chunk(0, null));

        subject.handle(request(null));

        verify(messageSender, never()).sendUpdateImagesRequested(any(), any());
    }

    /** A short chunk means the directory is exhausted; a successor would only bounce back empty. */
    @Test
    void shortChunkEndsTheSweep() {
        UUID lastId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(chunkProcessor.process(DIRECTORY_ID, null, CHUNK_SIZE))
                .thenReturn(new BlurHashChunkProcessor.Chunk(CHUNK_SIZE - 1, lastId));

        subject.handle(request(null));

        verify(messageSender, never()).sendUpdateImagesRequested(any(), any());
    }

    private static UpdateImagesRequestedData request(UUID afterId) {
        return UpdateImagesRequestedData.builder()
                .eventType(EventType.UPDATE_IMAGES_REQUESTED)
                .directoryEntityId(DIRECTORY_ID)
                .directoryName(DIRECTORY_NAME)
                .afterId(afterId)
                .build();
    }
}
