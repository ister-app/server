package app.ister.worker.scanner.scanners;

import app.ister.core.entitiy.BaseEntity;
import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.OtherPathFileEntity;
import app.ister.core.enums.PathFileType;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NfoScannerTest {
    @InjectMocks
    NfoScanner subject;
    @Mock
    private OtherPathFileRepository otherPathFileRepository;
    @Mock
    private MessageSender messageSender;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private BasicFileAttributes basicFileAttributes;

    @Test
    void analyzable() {
        assertTrue(subject.analyzable(Path.of("/disk/shows/SHOW (2024)/tvshow.nfo"), true, 0));
        assertFalse(subject.analyzable(Path.of("/disk/shows/SHOW (2024)/tvshowWithWrongName.nfo"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01/s01e01.nfo"), true, 0));
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01/tvshow.nfo"), true, 0));
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01/s01e01.nfo"), false, 0));
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01/s01e01.mkv"), true, 0));
    }

    @Test
    void analyzeExistingFile() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();
        Path path = Path.of("/path");
        OtherPathFileEntity otherPathFileEntity = OtherPathFileEntity.builder().build();

        when(otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString())).thenReturn(Optional.of(otherPathFileEntity));

        assertEquals(Optional.of(otherPathFileEntity), subject.analyze(directoryEntity, path, true, 0));
    }

    @Test
    void analyzeNoneExistingFile() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(UUID.randomUUID()).build();
        Path path = Path.of("/path");

        OtherPathFileEntity expected = OtherPathFileEntity.builder()
                .directoryEntityId(directoryEntity.getId())
                .pathFileType(PathFileType.NFO)
                .path(path.toString()).build();

        when(otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString())).thenReturn(Optional.empty());

        Optional<BaseEntity> result = subject.analyze(directoryEntity, path, true, 0);

        assertEquals(expected, result.orElseThrow());

        verify(otherPathFileRepository).save(expected);
        verify(messageSender).sendNfoFileFound(any(NfoFileFoundData.class));
    }
}