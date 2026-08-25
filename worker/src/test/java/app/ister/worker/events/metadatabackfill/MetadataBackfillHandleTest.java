package app.ister.worker.events.metadatabackfill;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.MetadataBackfillRequestedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MetadataBackfillHandleTest {

    @InjectMocks
    private MetadataBackfillHandle subject;

    @Mock
    private MetadataBackfillService metadataBackfillService;

    @Test
    void handles() {
        assertEquals(EventType.METADATA_BACKFILL_REQUESTED, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        MetadataBackfillRequestedData data = MetadataBackfillRequestedData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleRunsEveryBackfillStepWithTheLibraryScope() {
        UUID libraryId = UUID.randomUUID();

        subject.handle(MetadataBackfillRequestedData.builder()
                .eventType(EventType.METADATA_BACKFILL_REQUESTED)
                .libraryId(libraryId)
                .build());

        verify(metadataBackfillService).dispatchMissingVideoMetadataEvents(libraryId);
        verify(metadataBackfillService).dispatchMissingPersonMetadataEvents(libraryId);
        verify(metadataBackfillService).dispatchMissingMusicMetadataEvents(libraryId);
        verify(metadataBackfillService).dispatchMissingBookMetadataEvents(libraryId);
        verify(metadataBackfillService).applyBookSeriesHeuristics(libraryId);
        verify(metadataBackfillService).dispatchBookSeriesNfoEvents(libraryId);
        verify(metadataBackfillService).dispatchMissingComicMetadataEvents(libraryId);
    }
}
