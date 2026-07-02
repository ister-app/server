package app.ister.disk.events.pretranscode;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.PreTranscodeRecentlyWatchedData;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.core.service.MessageSender;
import app.ister.core.service.PreTranscodeService;
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
        Set<UUID> ids = Set.of(id1, id2);

        PreTranscodeRecentlyWatchedData data = PreTranscodeRecentlyWatchedData.builder()
                .eventType(EventType.PRE_TRANSCODE_RECENTLY_WATCHED)
                .diskName("disk1")
                .build();

        when(preTranscodeService.collectMediaFileIdsToPreTranscode("disk1")).thenReturn(ids);

        subject.handle(data);

        ArgumentCaptor<TranscodeRequestedData> captor = ArgumentCaptor.forClass(TranscodeRequestedData.class);
        verify(messageSender, times(2)).sendTranscodeRequested(captor.capture(), org.mockito.ArgumentMatchers.eq("disk1"));

        List<TranscodeRequestedData> sent = captor.getAllValues();
        assertTrue(sent.stream().allMatch(d -> Boolean.TRUE.equals(d.getPreTranscode())));
        assertTrue(sent.stream().allMatch(d -> Boolean.FALSE.equals(d.getDirect())));
        assertTrue(sent.stream().allMatch(d -> Boolean.TRUE.equals(d.getTranscode())));
    }

    @Test
    void handleWritesKeepFile() throws IOException {
        UUID id1 = UUID.randomUUID();
        Set<UUID> ids = Set.of(id1);

        PreTranscodeRecentlyWatchedData data = PreTranscodeRecentlyWatchedData.builder()
                .eventType(EventType.PRE_TRANSCODE_RECENTLY_WATCHED)
                .diskName("diskA")
                .build();

        when(preTranscodeService.collectMediaFileIdsToPreTranscode("diskA")).thenReturn(ids);

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

        when(preTranscodeService.collectMediaFileIdsToPreTranscode("diskB")).thenReturn(Set.of());

        subject.handle(data);

        Path keepFile = tempDir.resolve("pretranscode_keep_diskB.txt");
        assertTrue(Files.exists(keepFile));
        assertEquals("", Files.readString(keepFile));
    }
}
