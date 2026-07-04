package app.ister.disk.scanner.scanners;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.utils.Jaffree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AudioScannerTest {

    @InjectMocks
    private AudioScanner subject;

    @Mock
    private ScannerHelperService scannerHelperService;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private MessageSender messageSender;
    @Mock
    private Jaffree jaffree;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ========== analyzable(path, isRegularFile, size) ==========

    @Test
    void analyzableReturnsTrueForRegularFile() {
        assertTrue(subject.analyzable(Path.of("/music/Artist/Album/01 - Track.flac"), true, 1000));
    }

    @Test
    void analyzableReturnsFalseForNonRegularFile() {
        assertFalse(subject.analyzable(Path.of("/music/Artist/Album/"), false, 0));
    }

    // ========== analyzable(path, isRegularFile, directoryEntity) ==========

    @Test
    void analyzableWithDirectoryReturnsFalseForDirectory() {
        DirectoryEntity dir = buildMusicDir("/music");
        assertFalse(subject.analyzable(Path.of("/music/Artist/Album/"), false, dir));
    }

    @Test
    void analyzableWithDirectoryReturnsFalseWhenNoLibrary() {
        DirectoryEntity dir = DirectoryEntity.builder().path("/music").build();
        assertFalse(subject.analyzable(Path.of("/music/Artist/Album/01.flac"), true, dir));
    }

    @Test
    void analyzableWithDirectoryReturnsFalseWhenNotMusicLibrary() {
        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies").build();
        DirectoryEntity dir = DirectoryEntity.builder().path("/movies").libraryEntity(library).build();
        assertFalse(subject.analyzable(Path.of("/movies/Movie/movie.mp4"), true, dir));
    }

    @Test
    void analyzableWithDirectoryReturnsTrueForAudioFile() {
        DirectoryEntity dir = buildMusicDir("/music");
        assertTrue(subject.analyzable(Path.of("/music/Artist/Album/01 - Track.flac"), true, dir));
    }

    @Test
    void analyzableWithDirectoryReturnsFalseForNfoFile() {
        DirectoryEntity dir = buildMusicDir("/music");
        assertFalse(subject.analyzable(Path.of("/music/Artist/artist.nfo"), true, dir));
    }

    @Test
    void analyzableWithDirectoryReturnsFalseForImageFile() {
        DirectoryEntity dir = buildMusicDir("/music");
        assertFalse(subject.analyzable(Path.of("/music/Artist/artist.jpg"), true, dir));
    }

    // ========== analyze — new file ==========

    @Test
    void analyzeCreatesNewMediaFileAndSendsEvent() {
        DirectoryEntity dir = buildMusicDir("/music");
        Path audioPath = Path.of("/music/Artist/Album (2024)/01 - Track.flac");

        LibraryEntity library = dir.getLibraryEntity();
        PersonEntity artist = buildArtist(library);
        AlbumEntity album = buildAlbum(library, artist);
        TrackEntity track = buildTrack(album);

        when(scannerHelperService.getOrCreatePerson(library, "Artist")).thenReturn(artist);
        when(scannerHelperService.getOrCreateAlbum(library, artist, "Album", 2024)).thenReturn(album);
        when(scannerHelperService.getOrCreateTrack(artist, album, 1, 1)).thenReturn(track);
        when(mediaFileRepository.findByDirectoryEntityAndPath(dir, audioPath.toString()))
                .thenReturn(Optional.empty());

        var result = subject.analyze(dir, audioPath, true, 5000);

        assertTrue(result.isPresent());
        verify(mediaFileRepository).save(any(MediaFileEntity.class));
    }

    @Test
    void analyzeReturnsEmptyForNonAudioPath() {
        DirectoryEntity dir = buildMusicDir("/music");
        Path nfoPath = Path.of("/music/Artist/artist.nfo");

        var result = subject.analyze(dir, nfoPath, true, 100);

        assertTrue(result.isEmpty());
        verify(mediaFileRepository, never()).save(any());
    }

    @Test
    void analyzeDoesNotSaveWhenMediaFileExistsWithCorrectTrack() {
        DirectoryEntity dir = buildMusicDir("/music");
        Path audioPath = Path.of("/music/Artist/Album (2024)/01 - Track.flac");

        LibraryEntity library = dir.getLibraryEntity();
        PersonEntity artist = buildArtist(library);
        AlbumEntity album = buildAlbum(library, artist);
        TrackEntity track = buildTrack(album);
        UUID trackId = UUID.randomUUID();
        ReflectionTestUtils.setField(track, "id", trackId);

        MediaFileEntity existing = MediaFileEntity.builder()
                .path(audioPath.toString())
                .trackEntity(track)
                .build();

        when(scannerHelperService.getOrCreatePerson(library, "Artist")).thenReturn(artist);
        when(scannerHelperService.getOrCreateAlbum(library, artist, "Album", 2024)).thenReturn(album);
        when(scannerHelperService.getOrCreateTrack(artist, album, 1, 1)).thenReturn(track);
        when(mediaFileRepository.findByDirectoryEntityAndPath(dir, audioPath.toString()))
                .thenReturn(Optional.of(existing));

        var result = subject.analyze(dir, audioPath, true, 5000);

        assertTrue(result.isPresent());
        verify(mediaFileRepository, never()).save(any());
    }

    @Test
    void analyzeFixesWrongTrackAssociation() {
        DirectoryEntity dir = buildMusicDir("/music");
        Path audioPath = Path.of("/music/Artist/Album (2024)/01 - Track.flac");

        LibraryEntity library = dir.getLibraryEntity();
        PersonEntity artist = buildArtist(library);
        AlbumEntity album = buildAlbum(library, artist);
        TrackEntity correctTrack = buildTrack(album);
        UUID correctTrackId = UUID.randomUUID();
        ReflectionTestUtils.setField(correctTrack, "id", correctTrackId);

        TrackEntity wrongTrack = buildTrack(album);
        UUID wrongTrackId = UUID.randomUUID();
        ReflectionTestUtils.setField(wrongTrack, "id", wrongTrackId);

        MediaFileEntity existing = MediaFileEntity.builder()
                .path(audioPath.toString())
                .trackEntity(wrongTrack)
                .build();

        when(scannerHelperService.getOrCreatePerson(library, "Artist")).thenReturn(artist);
        when(scannerHelperService.getOrCreateAlbum(library, artist, "Album", 2024)).thenReturn(album);
        when(scannerHelperService.getOrCreateTrack(artist, album, 1, 1)).thenReturn(correctTrack);
        when(mediaFileRepository.findByDirectoryEntityAndPath(dir, audioPath.toString()))
                .thenReturn(Optional.of(existing));

        var result = subject.analyze(dir, audioPath, true, 5000);

        assertTrue(result.isPresent());
        verify(mediaFileRepository).save(existing);
    }

    @Test
    void analyzeFixesNullTrackOnExistingFile() {
        DirectoryEntity dir = buildMusicDir("/music");
        Path audioPath = Path.of("/music/Artist/Album (2024)/01 - Track.flac");

        LibraryEntity library = dir.getLibraryEntity();
        PersonEntity artist = buildArtist(library);
        AlbumEntity album = buildAlbum(library, artist);
        TrackEntity track = buildTrack(album);
        UUID trackId = UUID.randomUUID();
        ReflectionTestUtils.setField(track, "id", trackId);

        MediaFileEntity existing = MediaFileEntity.builder()
                .path(audioPath.toString())
                .trackEntity(null)
                .build();

        when(scannerHelperService.getOrCreatePerson(library, "Artist")).thenReturn(artist);
        when(scannerHelperService.getOrCreateAlbum(library, artist, "Album", 2024)).thenReturn(album);
        when(scannerHelperService.getOrCreateTrack(artist, album, 1, 1)).thenReturn(track);
        when(mediaFileRepository.findByDirectoryEntityAndPath(dir, audioPath.toString()))
                .thenReturn(Optional.of(existing));

        var result = subject.analyze(dir, audioPath, true, 5000);

        assertTrue(result.isPresent());
        verify(mediaFileRepository).save(existing);
    }

    // ========== helpers ==========

    private DirectoryEntity buildMusicDir(String path) {
        LibraryEntity library = LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC)
                .name("Music")
                .build();
        return DirectoryEntity.builder()
                .name("music-dir")
                .path(path)
                .libraryEntity(library)
                .build();
    }

    private PersonEntity buildArtist(LibraryEntity library) {
        return PersonEntity.builder()
                .libraryEntity(library)
                .name("Artist")
                .build();
    }

    private AlbumEntity buildAlbum(LibraryEntity library, PersonEntity artist) {
        return AlbumEntity.builder()
                .libraryEntity(library)
                .personEntity(artist)
                .name("Album")
                .releaseYear(2024)
                .build();
    }

    private TrackEntity buildTrack(AlbumEntity album) {
        PersonEntity artist = album.getPersonEntity();
        return TrackEntity.builder()
                .personEntity(artist)
                .albumEntity(album)
                .number(1)
                .discNumber(1)
                .build();
    }
}
