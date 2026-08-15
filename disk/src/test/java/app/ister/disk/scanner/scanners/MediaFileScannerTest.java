package app.ister.disk.scanner.scanners;

import app.ister.core.entity.*;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.repository.MediaFileEpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
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
    private MediaFileEpisodeRepository mediaFileEpisodeRepository;
    @Mock
    private MediaFileStreamRepository mediaFileStreamRepository;
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
        MediaFileEntity existing = MediaFileEntity.builder()
                .id(UUID.randomUUID())
                .path("/disk/shows/Show (2024)/Season 01/s01e01.mkv").build();
        Path path = Path.of("/disk/shows/Show (2024)/Season 01/s01e01.mkv");

        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 1, 1)).thenReturn(episode);
        when(mediaFileRepository.findByDirectoryEntityAndPath(directory, path.toString())).thenReturn(Optional.of(existing));

        subject.analyze(directory, path, true, 1024L);

        verify(mediaFileRepository, never()).save(any());
        verifyNoInteractions(messageSender);
    }

    @Test
    void analyzeMultiEpisodeFileCreatesAllEpisodesAndLinkRows() {
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("disk1").libraryEntity(library)
                .nodeEntity(NodeEntity.builder().name("node1").build())
                .build();
        EpisodeEntity episode6 = EpisodeEntity.builder().id(UUID.randomUUID()).build();
        EpisodeEntity episode7 = EpisodeEntity.builder().id(UUID.randomUUID()).build();
        Path path = Path.of("/disk/shows/Show (2024)/Season 04/s04e06-e07.mkv");

        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 4, 6)).thenReturn(episode6);
        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 4, 7)).thenReturn(episode7);
        when(mediaFileRepository.findByDirectoryEntityAndPath(directory, path.toString())).thenReturn(Optional.empty());

        Optional<BaseEntity> result = subject.analyze(directory, path, true, 1024L);

        assertTrue(result.isPresent());
        assertEquals(episode6, result.get());
        verify(mediaFileRepository).save(any(MediaFileEntity.class));
        verify(mediaFileEpisodeRepository).saveAll(argThat((Iterable<MediaFileEpisodeEntity> links) -> {
            var it = links.iterator();
            MediaFileEpisodeEntity first = it.next();
            MediaFileEpisodeEntity second = it.next();
            return !it.hasNext()
                    && first.getEpisodeEntityId().equals(episode6.getId()) && first.getPartNumber() == 0
                    && second.getEpisodeEntityId().equals(episode7.getId()) && second.getPartNumber() == 1;
        }));
        verify(messageSender).sendMediaFileFound(argThat((MediaFileFoundData data) ->
                data.getEpisodeEntityUUID().equals(episode6.getId())
                        && data.getEpisodeEntityUUIDs().equals(java.util.List.of(episode6.getId(), episode7.getId()))), eq("disk1"));
    }

    @Test
    void analyzeExistingMultiEpisodeFileWithoutLinksBackfillsAndReanalyzes() {
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("disk1").libraryEntity(library)
                .nodeEntity(NodeEntity.builder().name("node1").build())
                .build();
        EpisodeEntity episode6 = EpisodeEntity.builder().id(UUID.randomUUID()).build();
        EpisodeEntity episode7 = EpisodeEntity.builder().id(UUID.randomUUID()).build();
        Path path = Path.of("/disk/shows/Show (2024)/Season 04/s04e06-e07.mkv");
        MediaFileEntity existing = MediaFileEntity.builder().id(UUID.randomUUID()).path(path.toString()).build();

        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 4, 6)).thenReturn(episode6);
        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 4, 7)).thenReturn(episode7);
        when(mediaFileRepository.findByDirectoryEntityAndPath(directory, path.toString())).thenReturn(Optional.of(existing));
        when(mediaFileEpisodeRepository.findByMediaFileEntityIdOrderByPartNumber(existing.getId())).thenReturn(java.util.List.of());

        subject.analyze(directory, path, true, 1024L);

        verify(mediaFileRepository, never()).save(any());
        verify(mediaFileEpisodeRepository).saveAll(any());
        verify(messageSender).sendMediaFileFound(any(), eq("disk1"));
    }

    @Test
    void analyzeExistingMultiEpisodeFileWithLinksIsIdempotent() {
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directory = DirectoryEntity.builder()
                .id(UUID.randomUUID()).name("disk1").libraryEntity(library)
                .nodeEntity(NodeEntity.builder().name("node1").build())
                .build();
        EpisodeEntity episode6 = EpisodeEntity.builder().id(UUID.randomUUID()).build();
        EpisodeEntity episode7 = EpisodeEntity.builder().id(UUID.randomUUID()).build();
        Path path = Path.of("/disk/shows/Show (2024)/Season 04/s04e06-e07.mkv");
        MediaFileEntity existing = MediaFileEntity.builder().id(UUID.randomUUID()).path(path.toString()).build();

        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 4, 6)).thenReturn(episode6);
        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 4, 7)).thenReturn(episode7);
        when(mediaFileRepository.findByDirectoryEntityAndPath(directory, path.toString())).thenReturn(Optional.of(existing));
        when(mediaFileEpisodeRepository.findByMediaFileEntityIdOrderByPartNumber(existing.getId()))
                .thenReturn(java.util.List.of(MediaFileEpisodeEntity.builder().build()));

        subject.analyze(directory, path, true, 1024L);

        verify(mediaFileRepository, never()).save(any());
        verify(mediaFileEpisodeRepository, never()).saveAll(any());
        verifyNoInteractions(messageSender);
    }
}
