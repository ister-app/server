package app.ister.transcoder;

import app.ister.core.enums.EventType;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.transcoder.config.TranscoderQueueNamingConfig;
import app.ister.transcoder.events.HandleTranscodeRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HandleTranscodeRequestedTest {

    @InjectMocks
    private HandleTranscodeRequested handler;

    @Mock
    private HlsService hlsService;

    @Mock
    private TranscoderQueueNamingConfig transcoderQueueNamingConfig;

    @Test
    void preTranscodeTrueStartsAllPasses() throws Exception {
        UUID id = UUID.randomUUID();
        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(id)
                .direct(false)
                .transcode(true)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .preTranscode(true)
                .build();

        handler.handle(data);

        verify(hlsService).generateAllPlaylists(eq(id), eq(false), eq(true), eq(SubtitleFormat.WEBVTT));
        verify(hlsService).startAllPasses(eq(id), eq(false), eq(true));
    }

    @Test
    void preTranscodeFalseDoesNotStartPasses() throws Exception {
        UUID id = UUID.randomUUID();
        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(id)
                .direct(false)
                .transcode(true)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .preTranscode(false)
                .build();

        handler.handle(data);

        verify(hlsService).generateAllPlaylists(eq(id), eq(false), eq(true), eq(SubtitleFormat.WEBVTT));
        verify(hlsService, never()).startAllPasses(any(), anyBoolean(), anyBoolean());
    }
}
