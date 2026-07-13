package app.ister.transcoder;

import app.ister.core.EventHandlingException;
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

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HandleTranscodeRequestedTest {

    @InjectMocks
    private HandleTranscodeRequested handler;

    @Mock
    private HlsService hlsService;

    @Mock
    private HlsTranscodeService transcodeService;

    @Mock
    private TranscoderQueueNamingConfig transcoderQueueNamingConfig;

    @Test
    void handles() {
        assertEquals(EventType.TRANSCODE_REQUESTED, handler.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> handler.listener(data));
    }

    @Test
    void listenerWithCorrectEventTypeCallsHandle() throws Exception {
        UUID id = UUID.randomUUID();
        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(id)
                .direct(true)
                .transcode(false)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .preTranscode(false)
                .build();
        handler.listener(data);
        verify(hlsService).generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT);
    }

    @Test
    void handleSucceeds() throws Exception {
        UUID id = UUID.randomUUID();
        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(id)
                .direct(true)
                .transcode(false)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .preTranscode(false)
                .build();

        assertDoesNotThrow(() -> handler.handle(data));
        verify(hlsService).generateAllPlaylists(id, true, false, SubtitleFormat.WEBVTT);
    }

    @Test
    void handleThrowsWhenGeneratePlaylistsThrows() throws Exception {
        UUID id = UUID.randomUUID();
        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(id)
                .direct(true)
                .transcode(false)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .preTranscode(false)
                .build();

        doThrow(new IOException("ffprobe failure")).when(hlsService).generateAllPlaylists(any(), anyBoolean(), anyBoolean(), any());

        assertThrows(EventHandlingException.class, () -> handler.handle(data));
    }

    @Test
    void preTranscodeTrueStartsAllPassesWhenNoneActive() throws Exception {
        UUID id = UUID.randomUUID();
        when(transcodeService.hasAnyActiveOrCompletedPassForFile(id)).thenReturn(false);

        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(id)
                .direct(false)
                .transcode(true)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .preTranscode(true)
                .build();

        handler.handle(data);

        verify(hlsService).generateAllPlaylists(id, false, true, SubtitleFormat.WEBVTT);
        verify(hlsService).startAllPasses(id, false, true, PassFilter.preTranscode(null, null));
    }

    @Test
    void preTranscodePassesTheUsersLanguagesAndQualityCapOn() {
        UUID id = UUID.randomUUID();
        when(transcodeService.hasAnyActiveOrCompletedPassForFile(id)).thenReturn(false);

        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(id)
                .direct(false)
                .transcode(true)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .preTranscode(true)
                .audioLanguages(List.of("nld", "eng"))
                .maxVideoHeight(480)
                .build();

        handler.handle(data);

        verify(hlsService).startAllPasses(id, false, true,
                PassFilter.preTranscode(List.of("nld", "eng"), 480));
    }

    @Test
    void preTranscodeTrueSkipsPassesWhenAlreadyActive() throws Exception {
        UUID id = UUID.randomUUID();
        when(transcodeService.hasAnyActiveOrCompletedPassForFile(id)).thenReturn(true);

        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(id)
                .direct(false)
                .transcode(true)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .preTranscode(true)
                .build();

        handler.handle(data);

        verify(hlsService).generateAllPlaylists(id, false, true, SubtitleFormat.WEBVTT);
        verify(hlsService, never()).startAllPasses(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void keepUntilIsWrittenWhenPresent() {
        UUID id = UUID.randomUUID();
        long keepUntil = System.currentTimeMillis() + 3_600_000;
        TranscodeRequestedData data = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(id)
                .direct(false)
                .transcode(true)
                .subtitleFormat(SubtitleFormat.WEBVTT)
                .preTranscode(false)
                .keepUntilEpochMillis(keepUntil)
                .build();

        handler.handle(data);

        verify(transcodeService).extendKeepUntil(id, keepUntil);
    }

    @Test
    void keepUntilIsNotWrittenWhenAbsent() {
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

        verify(transcodeService, never()).extendKeepUntil(any(), anyLong());
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

        verify(hlsService).generateAllPlaylists(id, false, true, SubtitleFormat.WEBVTT);
        verify(hlsService, never()).startAllPasses(any(), anyBoolean(), anyBoolean());
    }
}
