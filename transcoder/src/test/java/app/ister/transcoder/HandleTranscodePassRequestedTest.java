package app.ister.transcoder;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.TranscodePassRequestedData;
import app.ister.transcoder.events.HandleTranscodePassRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HandleTranscodePassRequestedTest {

    @InjectMocks
    private HandleTranscodePassRequested subject;

    @Mock
    private HlsService hlsService;

    @Test
    void handles() {
        assertEquals(EventType.TRANSCODE_PASS_REQUESTED, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void listenerWithCorrectEventTypeCallsHandle() {
        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .passKey("stream_uuid_video_720p")
                .build();
        subject.listener(data);
        verify(hlsService).startPass(data);
    }

    @Test
    void handleSucceeds() {
        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .passKey("stream_uuid_video_720p")
                .build();

        assertDoesNotThrow(() -> subject.handle(data));
        verify(hlsService).startPass(data);
    }

    @Test
    void handleThrowsWhenHlsServiceThrows() {
        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .passKey("stream_uuid_video_720p")
                .build();

        doThrow(new RuntimeException("FFmpeg error")).when(hlsService).startPass(data);

        assertThrows(RuntimeException.class, () -> subject.handle(data));
    }
}
