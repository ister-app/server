package app.ister.server.events.fileScanRequested;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.enums.EventType;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.scanner.scanners.ImageScanner;
import app.ister.server.scanner.scanners.MediaFileScanner;
import app.ister.server.scanner.scanners.NfoScanner;
import app.ister.server.scanner.scanners.SubtitleScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileScanRequestedHandleTest {
    @Mock
    private DirectoryRepository directoryRepositoryMock;
    @Mock
    private MediaFileScanner mediaFileScanner;
    @Mock
    private ImageScanner imageScanner;
    @Mock
    private NfoScanner nfoScanner;
    @Mock
    private SubtitleScanner subtitleScanner;

    private FileScanRequestedHandle subject;

    @BeforeEach
    void setUp() {
        subject = new FileScanRequestedHandle(directoryRepositoryMock, mediaFileScanner, imageScanner, nfoScanner, subtitleScanner);
    }

    @Test
    void handles() {
        assertEquals(EventType.FILE_SCAN_REQUESTED, subject.handles());
    }

    @Test
    void listenerThrowNotSupported() {
        assertThrows(IllegalArgumentException.class, () -> subject.listener(FileScanRequestedData.builder().eventType(EventType.SHOW_FOUND).build()));
    }

    @Test
    void handle() {
        UUID directoryEntityUUID = UUID.randomUUID();
        Path path = Path.of("/path");
        FileScanRequestedData fileScanRequestedData = FileScanRequestedData.builder()
                .directoryEntityUUID(directoryEntityUUID)
                .path(path)
                .regularFile(true)
                .size(10)
                .build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(directoryEntityUUID).build();

        when(directoryRepositoryMock.findById(directoryEntityUUID)).thenReturn(Optional.of(directoryEntity));

        when(mediaFileScanner.analyzable(path, true, 10)).thenReturn(true);
        when(imageScanner.analyzable(path, true, 10)).thenReturn(false);
        when(nfoScanner.analyzable(path, true, 10)).thenReturn(false);
        when(subtitleScanner.analyzable(path, true, 10)).thenReturn(false);

        assertTrue(subject.handle(fileScanRequestedData));

        verify(mediaFileScanner).analyze(directoryEntity, path, true, 10);
    }
}