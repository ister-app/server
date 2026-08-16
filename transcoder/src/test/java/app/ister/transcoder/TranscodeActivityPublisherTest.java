package app.ister.transcoder;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.eventdata.TranscodeActivityStatusData;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeActivityPublisherTest {

    @Mock
    private HlsTranscodeService transcodeService;

    @Mock
    private MessageSender messageSender;

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TranscodeActivityPublisher subject;

    private final UUID mediaFileId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subject = new TranscodeActivityPublisher(transcodeService, messageSender, mediaFileRepository,
                transactionManager, "node1");
    }

    private HlsTranscodeService.RunningPassView pass(String quality) {
        return new HlsTranscodeService.RunningPassView(mediaFileId + "_" + quality, mediaFileId.toString(),
                false, 1000);
    }

    @Test
    void staysSilentWhileIdle() {
        when(transcodeService.runningPassesSnapshot()).thenReturn(List.of());

        subject.publishIfChanged();
        subject.publishIfChanged();

        verify(messageSender, times(0)).sendStatus(any());
    }

    @Test
    void publishesRunningPassesWithTitleAndQuality() {
        when(transcodeService.runningPassesSnapshot()).thenReturn(List.of(pass("video_720p")));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(
                MediaFileEntity.builder().path("/media/movies/Big Movie (2020).mkv").build()));

        subject.publishIfChanged();

        ArgumentCaptor<TranscodeActivityStatusData> captor = ArgumentCaptor.forClass(TranscodeActivityStatusData.class);
        verify(messageSender).sendStatus(captor.capture());
        TranscodeActivityStatusData.TranscodePass published = captor.getValue().getPasses().getFirst();
        assertEquals("Big Movie (2020).mkv", published.getTitle());
        assertEquals("video_720p", published.getQuality());
        assertEquals("node1", captor.getValue().getNodeName());
    }

    @Test
    void doesNotRepublishUnchangedPassesImmediately() {
        when(transcodeService.runningPassesSnapshot()).thenReturn(List.of(pass("video_720p")));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.empty());

        subject.publishIfChanged();
        subject.publishIfChanged();

        verify(messageSender, times(1)).sendStatus(any());
    }

    @Test
    void publishesOneFinalEmptyListWhenTheLastPassFinishes() {
        when(transcodeService.runningPassesSnapshot()).thenReturn(List.of(pass("video_720p")));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.empty());
        subject.publishIfChanged();

        when(transcodeService.runningPassesSnapshot()).thenReturn(List.of());
        subject.publishIfChanged();
        subject.publishIfChanged();

        ArgumentCaptor<TranscodeActivityStatusData> captor = ArgumentCaptor.forClass(TranscodeActivityStatusData.class);
        verify(messageSender, times(2)).sendStatus(captor.capture());
        assertTrue(captor.getValue().getPasses().isEmpty());
    }

    @Test
    void aMissingMediaFileRowLeavesTheTitleNull() {
        when(transcodeService.runningPassesSnapshot()).thenReturn(List.of(pass("video_720p")));
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.empty());

        subject.publishIfChanged();

        ArgumentCaptor<TranscodeActivityStatusData> captor = ArgumentCaptor.forClass(TranscodeActivityStatusData.class);
        verify(messageSender).sendStatus(captor.capture());
        assertNull(captor.getValue().getPasses().getFirst().getTitle());
    }
}
