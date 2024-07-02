package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.OtherPathFileEntity;
import app.ister.server.enums.PathFileType;
import app.ister.server.events.nfofilefound.NfoFileFoundData;
import app.ister.server.repository.OtherPathFileRepository;
import app.ister.server.repository.ShowRepository;
import app.ister.server.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
                .directoryEntity(directoryEntity)
                .pathFileType(PathFileType.NFO)
                .path(path.toString()).build();

        when(otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString())).thenReturn(Optional.empty());

        Optional<BaseEntity> result = subject.analyze(directoryEntity, path, true, 0);

        assertEquals(expected, result.orElseThrow());

        verify(otherPathFileRepository).save(expected);
        verify(messageSender).sendNfoFileFound(any(NfoFileFoundData.class));
    }
}