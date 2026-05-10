package app.ister.worker;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.PreTranscodeRecentlyWatchedData;
import app.ister.core.service.MessageSender;
import app.ister.worker.config.WorkerDiskConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreTranscodeSchedulerTest {

    @InjectMocks
    private PreTranscodeScheduler subject;

    @Mock
    private MessageSender messageSender;

    @Mock
    private WorkerDiskConfig workerDiskConfig;

    private WorkerDiskConfig.DiskEntry diskEntry(String name) {
        WorkerDiskConfig.DiskEntry entry = new WorkerDiskConfig.DiskEntry();
        entry.setName(name);
        return entry;
    }

    @Test
    void schedulePreTranscodeSendsMessageForEachDirectory() {
        when(workerDiskConfig.getDirectories()).thenReturn(List.of(diskEntry("disk1"), diskEntry("disk2")));

        subject.schedulePreTranscode();

        ArgumentCaptor<PreTranscodeRecentlyWatchedData> dataCaptor =
                ArgumentCaptor.forClass(PreTranscodeRecentlyWatchedData.class);
        ArgumentCaptor<String> queueCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageSender, times(2)).sendPreTranscodeRecentlyWatched(dataCaptor.capture(), queueCaptor.capture());

        List<PreTranscodeRecentlyWatchedData> sentData = dataCaptor.getAllValues();
        assertEquals(EventType.PRE_TRANSCODE_RECENTLY_WATCHED, sentData.get(0).getEventType());
        assertEquals("disk1", sentData.get(0).getDiskName());
        assertEquals("disk1", queueCaptor.getAllValues().get(0));
        assertEquals("disk2", sentData.get(1).getDiskName());
        assertEquals("disk2", queueCaptor.getAllValues().get(1));
    }

    @Test
    void schedulePreTranscodeDoesNothingWhenNoDirectoriesConfigured() {
        when(workerDiskConfig.getDirectories()).thenReturn(List.of());

        subject.schedulePreTranscode();

        verify(messageSender, times(0)).sendPreTranscodeRecentlyWatched(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
