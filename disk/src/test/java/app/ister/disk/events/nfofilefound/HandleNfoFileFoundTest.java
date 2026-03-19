package app.ister.disk.events.nfofilefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.service.ScannerHelperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleNfoFileFoundTest {

    @InjectMocks
    private HandleNfoFileFound subject;

    @Mock
    private DirectoryRepository directoryRepository;

    @Mock
    private MetadataRepository metadataRepository;

    @Mock
    private ScannerHelperService scannerHelperService;

    @Test
    void handles() {
        assertEquals(EventType.NFO_FILE_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        NfoFileFoundData data = NfoFileFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleWithNonMatchingPath() {
        UUID uuid = UUID.randomUUID();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path("/disk/movies/Movie (2024)/movie.nfo")
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));

        assertTrue(subject.handle(data));
    }
}
