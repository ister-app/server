package app.ister.disk.events.newdirectoriesscanrequested;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.NewDirectoriesScanRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.disk.scanner.LibraryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleNewDirectoriesScanRequestedTest {

    @InjectMocks
    private HandleNewDirectoriesScanRequested subject;

    @Mock
    private DirectoryRepository directoryRepository;

    @Mock
    private LibraryScanner libraryScanner;

    @Test
    void handles() {
        assertEquals(EventType.NEW_DIRECTORIES_SCAN_REQUEST, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        NewDirectoriesScanRequestedData data = NewDirectoriesScanRequestedData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handle() throws IOException {
        UUID uuid = UUID.randomUUID();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).build();
        NewDirectoriesScanRequestedData data = NewDirectoriesScanRequestedData.builder()
                .directoryEntityUUID(uuid)
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));

        assertTrue(subject.handle(data));

        verify(libraryScanner).scanDirectory(directoryEntity);
    }
}
