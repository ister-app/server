package app.ister.disk.scanner.scanners;

import app.ister.core.entity.*;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaFileScannerTest {
    @InjectMocks
    MediaFileScanner subject;
    @Mock
    private ScannerHelperService scannerHelperService;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private MessageSender messageSender;

    @Test
    void analyzable() {
        assertTrue(subject.analyzable(Path.of("/disk/movies/Movie (2024).mkv"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/s01e01.mkv"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/shows/SHOW (2024)/s01e01.mkv"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/s02E03.mkv"), true, 0));
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/s01e01.png"), true, 0));
    }

    @Test
    void analyzeEpisodeCreatesMediaFileAndSendsEvent() {
        UUID dirId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .id(dirId).name("disk1").libraryEntity(library)
                .nodeEntity(NodeEntity.builder().name("node1").build())
                .build();
        EpisodeEntity episode = EpisodeEntity.builder().id(UUID.randomUUID()).build();
        Path path = Path.of("/disk/shows/Show (2024)/Season 01/s01e01.mkv");

        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 1, 1)).thenReturn(episode);
        when(mediaFileRepository.findByDirectoryEntityAndPath(directory, path.toString())).thenReturn(Optional.empty());

        Optional<BaseEntity> result = subject.analyze(directory, path, true, 1024L);

        assertTrue(result.isPresent());
        assertEquals(episode, result.get());
        verify(mediaFileRepository).save(any(MediaFileEntity.class));
        verify(messageSender).sendMediaFileFound(any(), eq("disk1"));
    }

    @Test
    void analyzeMovieCreatesMediaFileAndSendsEvent() {
        UUID dirId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .id(dirId).name("disk1").libraryEntity(library)
                .nodeEntity(NodeEntity.builder().name("node1").build())
                .build();
        MovieEntity movie = MovieEntity.builder().id(UUID.randomUUID()).build();
        Path path = Path.of("/disk/movies/Movie (2024).mkv");

        when(scannerHelperService.getOrCreateMovie(library, "Movie", 2024)).thenReturn(movie);
        when(mediaFileRepository.findByDirectoryEntityAndPath(directory, path.toString())).thenReturn(Optional.empty());

        Optional<BaseEntity> result = subject.analyze(directory, path, true, 2048L);

        assertTrue(result.isEmpty());
        verify(mediaFileRepository).save(any(MediaFileEntity.class));
        verify(messageSender).sendMediaFileFound(any(), eq("disk1"));
    }

    @Test
    void analyzeSkipsAlreadyExistingMediaFile() {
        UUID dirId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .id(dirId).name("disk1").libraryEntity(library)
                .nodeEntity(NodeEntity.builder().name("node1").build())
                .build();
        EpisodeEntity episode = EpisodeEntity.builder().id(UUID.randomUUID()).build();
        MediaFileEntity existing = MediaFileEntity.builder().path("/disk/shows/Show (2024)/Season 01/s01e01.mkv").build();
        Path path = Path.of("/disk/shows/Show (2024)/Season 01/s01e01.mkv");

        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 1, 1)).thenReturn(episode);
        when(mediaFileRepository.findByDirectoryEntityAndPath(directory, path.toString())).thenReturn(Optional.of(existing));

        subject.analyze(directory, path, true, 1024L);

        verify(mediaFileRepository, never()).save(any());
        verifyNoInteractions(messageSender);
    }
}
