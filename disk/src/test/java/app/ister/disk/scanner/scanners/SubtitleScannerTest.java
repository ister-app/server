package app.ister.disk.scanner.scanners;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.OtherPathFileEntity;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubtitleScannerTest {

    @InjectMocks
    SubtitleScanner subject;

    @Mock
    private OtherPathFileRepository otherPathFileRepository;

    @Mock
    private MessageSender messageSender;

    @Test
    void analyzableForEpisodeSubtitle() {
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01/s01e01.nl.srt"), true, 0));
    }

    @Test
    void notAnalyzableForNonSubtitleFile() {
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01/s01e01.mkv"), true, 0));
    }

    @Test
    void notAnalyzableForDirectory() {
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01/s01e01.nl.srt"), false, 0));
    }

    @Test
    void notAnalyzableForNonEpisodePath() {
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/tvshow.srt"), true, 0));
    }

    @Test
    void analyzeCreatesNewEntityWhenNotExists() {
        UUID dirId = UUID.randomUUID();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(dirId).name("disk1").build();
        Path path = Path.of("/disk/shows/Show (2024)/Season 01/s01e01.nl.srt");

        when(otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString())).thenReturn(Optional.empty());

        var result = subject.analyze(directoryEntity, path, true, 0L);

        assertTrue(result.isPresent());
        verify(otherPathFileRepository).save(any(OtherPathFileEntity.class));
        verify(messageSender).sendSubtitleFileFound(any(), eq("disk1"));
    }

    @Test
    void analyzeReturnsExistingEntityWhenAlreadyExists() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(UUID.randomUUID()).name("disk1").build();
        Path path = Path.of("/disk/shows/Show (2024)/Season 01/s01e01.nl.srt");
        OtherPathFileEntity existing = OtherPathFileEntity.builder().path(path.toString()).build();

        when(otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString())).thenReturn(Optional.of(existing));

        var result = subject.analyze(directoryEntity, path, true, 0L);

        assertTrue(result.isPresent());
        verify(otherPathFileRepository, never()).save(any());
    }
}
