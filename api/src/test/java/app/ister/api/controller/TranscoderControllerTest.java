package app.ister.api.controller;

import app.ister.transcoder.TranscodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranscoderControllerTest {

    @InjectMocks
    private TranscoderController subject;

    @Mock
    private TranscodeService transcodeService;

    @Test
    void stopTranscodingDelegatesToService() {
        UUID id = UUID.randomUUID();
        when(transcodeService.stopTranscoding(id)).thenReturn(true);

        boolean result = subject.stopTranscoding(id);

        assertTrue(result);
        verify(transcodeService).stopTranscoding(id);
    }

    @Test
    void readyTranscodingDelegatesToService() {
        UUID id = UUID.randomUUID();
        when(transcodeService.readyTranscoding(id)).thenReturn(true);

        boolean result = subject.readyTranscoding(id);

        assertTrue(result);
        verify(transcodeService).readyTranscoding(id);
    }

    @Test
    void startTranscodingDelegatesToService() throws IOException {
        UUID playQueueId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(transcodeService.startTranscoding(playQueueId, mediaFileId, 30, Optional.empty(), Optional.empty()))
                .thenReturn(sessionId);

        UUID result = subject.startTranscoding(playQueueId, mediaFileId, 30, Optional.empty(), Optional.empty());

        assertEquals(sessionId, result);
    }

    @Test
    void startTranscodingWithAudioAndSubtitleDelegatesToService() throws IOException {
        UUID playQueueId = UUID.randomUUID();
        UUID mediaFileId = UUID.randomUUID();
        UUID audioId = UUID.randomUUID();
        UUID subtitleId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(transcodeService.startTranscoding(playQueueId, mediaFileId, 0, Optional.of(audioId), Optional.of(subtitleId)))
                .thenReturn(sessionId);

        UUID result = subject.startTranscoding(playQueueId, mediaFileId, 0, Optional.of(audioId), Optional.of(subtitleId));

        assertEquals(sessionId, result);
    }
}
