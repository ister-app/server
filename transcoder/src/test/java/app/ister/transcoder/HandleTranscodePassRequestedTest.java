package app.ister.transcoder;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.TranscodePassRequestedData;
import app.ister.transcoder.events.HandleTranscodePassRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void handleReturnsTrueOnSuccess() {
        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .passKey("stream_uuid_video_720p")
                .build();

        assertTrue(subject.handle(data));
        verify(hlsService).startPass(data);
    }

    @Test
    void handleReturnsFalseWhenHlsServiceThrows() {
        TranscodePassRequestedData data = TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .passKey("stream_uuid_video_720p")
                .build();

        doThrow(new RuntimeException("FFmpeg error")).when(hlsService).startPass(data);

        assertFalse(subject.handle(data));
    }
}
