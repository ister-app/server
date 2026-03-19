package app.ister.worker.events.showfound;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ShowFoundData;
import app.ister.core.repository.ShowRepository;
import app.ister.worker.events.TMDBMetadata.ImageDownloadService;
import app.ister.worker.events.TMDBMetadata.MetadataSave;
import app.ister.worker.events.TMDBMetadata.ShowMetadata;
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
class HandleShowFoundTest {

    @InjectMocks
    private HandleShowFound subject;

    @Mock
    private ShowRepository showRepository;

    @Mock
    private ShowMetadata showMetadata;

    @Mock
    private MetadataSave metaDataSave;

    @Mock
    private ImageDownloadService imageDownloadService;

    @Test
    void handles() {
        assertEquals(EventType.SHOW_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleReturnsTrueImmediatelyWhenNoApiKey() {
        ReflectionTestUtils.setField(subject, "apikey", "");
        ShowFoundData data = ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .build();

        assertTrue(subject.handle(data));

        verifyNoInteractions(showRepository, showMetadata, metaDataSave, imageDownloadService);
    }
}
