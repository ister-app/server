package app.ister.disk.events.nfofilefound;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.OtherPathFileEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.PathFileType;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.ScannerHelperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
    private OtherPathFileRepository otherPathFileRepository;

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

        subject.handle(data);
    }

    @Test
    void analyzeWithShowPath() {
        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).libraryEntity(library).build();
        ShowEntity show = ShowEntity.builder().build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path("/disk/shows/Show (2024)/tvshow.nfo")
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateShow(library, "Show", 2024)).thenReturn(show);

        subject.handle(data);

        verify(scannerHelperService).getOrCreateShow(library, "Show", 2024);
    }

    @Test
    void analyzeWithEpisodePath() {
        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).libraryEntity(library).build();
        EpisodeEntity episode = EpisodeEntity.builder().build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path("/disk/shows/Show (2024)/Season 01/s01e01.nfo")
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 1, 1)).thenReturn(episode);

        subject.handle(data);

        verify(scannerHelperService).getOrCreateEpisode(library, "Show", 2024, 1, 1);
    }

    @Test
    void analyzeWithShowPathSuccessfulParsing(@TempDir Path tempDir) throws IOException {
        Path showDir = tempDir.resolve("Show (2024)");
        Files.createDirectories(showDir);
        Path nfoFile = showDir.resolve("tvshow.nfo");
        try (var in = HandleNfoFileFoundTest.class.getResourceAsStream("/nfo/tvshow.nfo")) {
            Files.write(nfoFile, in.readAllBytes());
        }

        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).libraryEntity(library).build();
        ShowEntity show = ShowEntity.builder().build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path(nfoFile.toString())
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateShow(library, "Show", 2024)).thenReturn(show);

        subject.handle(data);

        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void analyzeWithEpisodePathSuccessfulParsing(@TempDir Path tempDir) throws IOException {
        Path episodeDir = tempDir.resolve("Show (2024)").resolve("Season 01");
        Files.createDirectories(episodeDir);
        Path nfoFile = episodeDir.resolve("s01e01.nfo");
        try (var in = HandleNfoFileFoundTest.class.getResourceAsStream("/nfo/episode.nfo")) {
            Files.write(nfoFile, in.readAllBytes());
        }

        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).libraryEntity(library).build();
        EpisodeEntity episode = EpisodeEntity.builder().build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path(nfoFile.toString())
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 1, 1)).thenReturn(episode);

        subject.handle(data);

        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void analyzeWithMoviePath() {
        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).libraryEntity(library).build();
        MovieEntity movie = MovieEntity.builder().build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path("/disk/movies/Movie (2024).nfo")
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateMovie(library, "Movie", 2024)).thenReturn(movie);

        subject.handle(data);

        verify(scannerHelperService).getOrCreateMovie(library, "Movie", 2024);
    }

    @Test
    void analyzeWithMoviePathSuccessfulParsing(@TempDir Path tempDir) throws IOException {
        Path nfoFile = tempDir.resolve("Inception (2010).nfo");
        try (var in = HandleNfoFileFoundTest.class.getResourceAsStream("/nfo/movie.nfo")) {
            Files.write(nfoFile, in.readAllBytes());
        }

        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).libraryEntity(library).build();
        MovieEntity movie = MovieEntity.builder().build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path(nfoFile.toString())
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateMovie(library, "Inception", 2010)).thenReturn(movie);

        subject.handle(data);

        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void analyzeArtistNfoPath() {
        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).path("/music").libraryEntity(library).build();
        ArtistEntity artist = ArtistEntity.builder().libraryEntity(library).name("The Beatles").build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path("/music/The Beatles/artist.nfo")
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateArtist(library, "The Beatles")).thenReturn(artist);

        subject.handle(data);
        verify(scannerHelperService).getOrCreateArtist(library, "The Beatles");
    }

    @Test
    void analyzeArtistNfoWithSuccessfulParsing(@TempDir Path tempDir) throws IOException {
        Path artistDir = tempDir.resolve("The Beatles");
        Files.createDirectories(artistDir);
        Path nfoFile = artistDir.resolve("artist.nfo");
        try (var in = HandleNfoFileFoundTest.class.getResourceAsStream("/nfo/artist.nfo")) {
            Files.write(nfoFile, in.readAllBytes());
        }

        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).path(tempDir.toString()).libraryEntity(library).build();
        ArtistEntity artist = ArtistEntity.builder().libraryEntity(library).name("The Beatles").build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path(nfoFile.toString())
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateArtist(library, "The Beatles")).thenReturn(artist);

        subject.handle(data);
        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void analyzeAlbumNfoPath() {
        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).path("/music").libraryEntity(library).build();
        ArtistEntity artist = ArtistEntity.builder().libraryEntity(library).name("The Beatles").build();
        AlbumEntity album = AlbumEntity.builder().libraryEntity(library).artistEntity(artist).name("Abbey Road").releaseYear(1969).build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path("/music/The Beatles/Abbey Road (1969)/album.nfo")
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateArtist(library, "The Beatles")).thenReturn(artist);
        when(scannerHelperService.getOrCreateAlbum(library, artist, "Abbey Road", 1969)).thenReturn(album);

        subject.handle(data);
        verify(scannerHelperService).getOrCreateAlbum(library, artist, "Abbey Road", 1969);
    }

    @Test
    void analyzeAlbumNfoWithSuccessfulParsing(@TempDir Path tempDir) throws IOException {
        Path albumDir = tempDir.resolve("The Beatles").resolve("Abbey Road (1969)");
        Files.createDirectories(albumDir);
        Path nfoFile = albumDir.resolve("album.nfo");
        try (var in = HandleNfoFileFoundTest.class.getResourceAsStream("/nfo/album.nfo")) {
            Files.write(nfoFile, in.readAllBytes());
        }

        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).path(tempDir.toString()).libraryEntity(library).build();
        ArtistEntity artist = ArtistEntity.builder().libraryEntity(library).name("The Beatles").build();
        AlbumEntity album = AlbumEntity.builder().libraryEntity(library).artistEntity(artist).name("Abbey Road").releaseYear(1969).build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path(nfoFile.toString())
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateArtist(library, "The Beatles")).thenReturn(artist);
        when(scannerHelperService.getOrCreateAlbum(library, artist, "Abbey Road", 1969)).thenReturn(album);

        subject.handle(data);
        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void analyzeWithEpisodePathSetsMetadataFkOnOtherPathFile(@TempDir Path tempDir) throws IOException {
        Path episodeDir = tempDir.resolve("Show (2024)").resolve("Season 01");
        Files.createDirectories(episodeDir);
        Path nfoFile = episodeDir.resolve("s01e01.nfo");
        try (var in = HandleNfoFileFoundTest.class.getResourceAsStream("/nfo/episode.nfo")) {
            Files.write(nfoFile, in.readAllBytes());
        }

        UUID uuid = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).libraryEntity(library).build();
        EpisodeEntity episode = EpisodeEntity.builder().build();
        MetadataEntity savedMetadata = MetadataEntity.builder().build();
        OtherPathFileEntity otherFile = OtherPathFileEntity.builder()
                .path(nfoFile.toString())
                .pathFileType(PathFileType.NFO)
                .build();
        NfoFileFoundData data = NfoFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path(nfoFile.toString())
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateEpisode(library, "Show", 2024, 1, 1)).thenReturn(episode);
        when(metadataRepository.save(any(MetadataEntity.class))).thenReturn(savedMetadata);
        when(otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, nfoFile.toString()))
                .thenReturn(Optional.of(otherFile));

        subject.handle(data);

        verify(otherPathFileRepository).save(otherFile);
        assertEquals(savedMetadata, otherFile.getMetadataEntity());
    }
}
