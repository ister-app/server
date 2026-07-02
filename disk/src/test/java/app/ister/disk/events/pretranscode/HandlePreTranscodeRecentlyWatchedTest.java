package app.ister.disk.events.pretranscode;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.eventdata.PreTranscodeRecentlyWatchedData;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.core.service.MessageSender;
import app.ister.core.service.PreTranscodeService;
import app.ister.core.service.PreTranscodeService.PreTranscodeCollection;
import app.ister.core.service.PreTranscodeService.UnanalyzedMediaFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandlePreTranscodeRecentlyWatchedTest {

    @Mock
    private PreTranscodeService preTranscodeService;
    @Mock
    private MessageSender messageSender;

    @TempDir
    Path tempDir;

    private HandlePreTranscodeRecentlyWatched subject;

    @BeforeEach
    void setUp() {
        subject = new HandlePreTranscodeRecentlyWatched(preTranscodeService, messageSender);
        ReflectionTestUtils.setField(subject, "tmpDir", tempDir.toString());
    }

    private static PreTranscodeCollection collection(Set<UUID> ids) {
        return new PreTranscodeCollection(ids, Set.of());
    }

    @Test
    void handles() {
        assertEquals(EventType.PRE_TRANSCODE_RECENTLY_WATCHED, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        PreTranscodeRecentlyWatchedData data = PreTranscodeRecentlyWatchedData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleSendsTranscodeRequestForEachMediaFile() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        PreTranscodeRecentlyWatchedData data = PreTranscodeRecentlyWatchedData.builder()
                .eventType(EventType.PRE_TRANSCODE_RECENTLY_WATCHED)
                .diskName("disk1")
                .build();

        when(preTranscodeService.collectMediaFilesToPreTranscode("disk1")).thenReturn(collection(Set.of(id1, id2)));

        subject.handle(data);

        ArgumentCaptor<TranscodeRequestedData> captor = ArgumentCaptor.forClass(TranscodeRequestedData.class);
        verify(messageSender, times(2)).sendTranscodeRequested(captor.capture(), eq("disk1"));

        List<TranscodeRequestedData> sent = captor.getAllValues();
        assertTrue(sent.stream().allMatch(d -> Boolean.TRUE.equals(d.getPreTranscode())));
        assertTrue(sent.stream().allMatch(d -> Boolean.FALSE.equals(d.getDirect())));
        assertTrue(sent.stream().allMatch(d -> Boolean.TRUE.equals(d.getTranscode())));
    }

    @Test
    void handleWritesKeepFile() throws IOException {
        UUID id1 = UUID.randomUUID();

        PreTranscodeRecentlyWatchedData data = PreTranscodeRecentlyWatchedData.builder()
                .eventType(EventType.PRE_TRANSCODE_RECENTLY_WATCHED)
                .diskName("diskA")
                .build();

        when(preTranscodeService.collectMediaFilesToPreTranscode("diskA")).thenReturn(collection(Set.of(id1)));

        subject.handle(data);

        Path keepFile = tempDir.resolve("pretranscode_keep_diskA.txt");
        assertTrue(Files.exists(keepFile));
        String content = Files.readString(keepFile);
        assertTrue(content.contains(id1.toString()));
    }

    @Test
    void handleWithEmptySetWritesEmptyKeepFile() throws IOException {
        PreTranscodeRecentlyWatchedData data = PreTranscodeRecentlyWatchedData.builder()
                .eventType(EventType.PRE_TRANSCODE_RECENTLY_WATCHED)
                .diskName("diskB")
                .build();

        when(preTranscodeService.collectMediaFilesToPreTranscode("diskB")).thenReturn(collection(Set.of()));

        subject.handle(data);

        Path keepFile = tempDir.resolve("pretranscode_keep_diskB.txt");
        assertTrue(Files.exists(keepFile));
        assertEquals("", Files.readString(keepFile));
    }

    @Test
    void unanalyzedFileTriggersAnalysisInsteadOfTranscode() {
        UUID mediaFileId = UUID.randomUUID();
        UUID directoryId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        UnanalyzedMediaFile unanalyzed = new UnanalyzedMediaFile(mediaFileId, directoryId, "/test/file.mkv", episodeId, null);

        PreTranscodeRecentlyWatchedData data = PreTranscodeRecentlyWatchedData.builder()
                .eventType(EventType.PRE_TRANSCODE_RECENTLY_WATCHED)
                .diskName("disk1")
                .build();

        when(preTranscodeService.collectMediaFilesToPreTranscode("disk1"))
                .thenReturn(new PreTranscodeCollection(Set.of(), Set.of(unanalyzed)));

        subject.handle(data);

        verify(messageSender, never()).sendTranscodeRequested(any(), any());
        ArgumentCaptor<MediaFileFoundData> captor = ArgumentCaptor.forClass(MediaFileFoundData.class);
        verify(messageSender).sendMediaFileFound(captor.capture(), eq("disk1"));
        MediaFileFoundData sent = captor.getValue();
        assertEquals(EventType.MEDIA_FILE_FOUND, sent.getEventType());
        assertEquals(directoryId, sent.getDirectoryEntityUUID());
        assertEquals(episodeId, sent.getEpisodeEntityUUID());
        assertEquals("/test/file.mkv", sent.getPath());
    }

    @Test
    void analysisForUnanalyzedFileIsOnlyRequestedOnce() {
        UnanalyzedMediaFile unanalyzed = new UnanalyzedMediaFile(
                UUID.randomUUID(), UUID.randomUUID(), "/test/file.mkv", UUID.randomUUID(), null);

        PreTranscodeRecentlyWatchedData data = PreTranscodeRecentlyWatchedData.builder()
                .eventType(EventType.PRE_TRANSCODE_RECENTLY_WATCHED)
                .diskName("disk1")
                .build();

        when(preTranscodeService.collectMediaFilesToPreTranscode("disk1"))
                .thenReturn(new PreTranscodeCollection(Set.of(), Set.of(unanalyzed)));

        subject.handle(data);
        subject.handle(data);

        verify(messageSender, times(1)).sendMediaFileFound(any(), eq("disk1"));
    }
}
