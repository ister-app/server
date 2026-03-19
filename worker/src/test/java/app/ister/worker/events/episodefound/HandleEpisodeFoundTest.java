package app.ister.worker.events.episodefound;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.repository.EpisodeRepository;
import app.ister.worker.events.TMDBMetadata.EpisodeMetadata;
import app.ister.worker.events.TMDBMetadata.ImageDownloadService;
import app.ister.worker.events.TMDBMetadata.MetadataSave;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HandleEpisodeFoundTest {

    @InjectMocks
    private HandleEpisodeFound subject;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private EpisodeMetadata episodeMetadata;

    @Mock
    private MetadataSave metaDataSave;

    @Mock
    private ImageDownloadService imageDownloadService;

    @Test
    void handles() {
        assertEquals(EventType.EPISODE_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleReturnsTrueImmediatelyWhenNoApiKey() {
        ReflectionTestUtils.setField(subject, "apikey", "");
        EpisodeFoundData data = EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .build();

        assertTrue(subject.handle(data));

        verifyNoInteractions(episodeRepository, episodeMetadata, metaDataSave, imageDownloadService);
    }
}
