package app.ister.disk.scanner.scanners;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.ImageRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import com.github.kokorin.jaffree.ffprobe.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageScannerTest {

    @InjectMocks
    ImageScanner subject;
    @Mock
    private ScannerHelperService scannerHelperService;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private BasicFileAttributes basicFileAttributes;
    @Mock
    private MessageSender messageSender;
    @Mock
    private Jaffree jaffree;

    @TempDir
    Path tempDir;

    @Test
    void analyzable() {
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/background.jpg"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/cover.png"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/cover.jpg"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/background.png"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/s01e01.jpg"), true, 0));
    }

    @Test
    void notAnalyzable() {
        assertFalse(subject.analyzable(Path.of("/disk/show/Show (2024)/s01e01.mkv"), true, 0));
        assertFalse(subject.analyzable(Path.of("/disk/show/Show (2024)/background.jpg"), false, 0));
        assertFalse(subject.analyzable(Path.of("/disk/show/Show (2024)/tvshow.nfo"), true, 0));
    }

    @Test
    void analyzeShowBackground() {
        ImageEntity result = (ImageEntity) subject.analyze(DirectoryEntity.builder().nodeEntity(NodeEntity.builder().name("disk1").build()).build(), Path.of("/disk/show/Show (2024)/background.jpg"), false, 0).orElseThrow();
        assertEquals(ImageType.BACKGROUND, result.getType());
        assertNull(result.getSeasonEntity());
    }

    @Test
    void analyzeSeasonBackground() {
        ImageEntity result = (ImageEntity) subject.analyze(DirectoryEntity.builder().nodeEntity(NodeEntity.builder().name("disk1").build()).build(), Path.of("/disk/show/Show (2024)/Season 01/background.jpg"), false, 0).orElseThrow();
        assertEquals(ImageType.BACKGROUND, result.getType());
        assertNull(result.getShowEntity());
    }

    @Test
    void analyzeEpisodeBackground() {
        ImageEntity result = (ImageEntity) subject.analyze(DirectoryEntity.builder().nodeEntity(NodeEntity.builder().name("disk1").build()).build(), Path.of("/disk/show/Show (2024)/Season 01/s01e01-thumb.jpg"), false, 0).orElseThrow();
        assertEquals(ImageType.BACKGROUND, result.getType());
        assertNull(result.getShowEntity());
    }

    @Test
    void analyzeEmpty() {
        var result = subject.analyze(DirectoryEntity.builder().nodeEntity(NodeEntity.builder().name("disk1").build()).build(), Path.of("/disk/show/Show (2024)/Season 01/s01e01.mkv"), false, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    void analyzableWithDirectoryEntityReturnsFalseWhenNotRegularFile() {
        DirectoryEntity dir = DirectoryEntity.builder().build();
        assertFalse(subject.analyzable(Path.of("/disk/show/background.jpg"), false, 0, dir));
    }

    @Test
    void analyzableWithDirectoryEntityMusicLibraryDelegatesToMusicPathObject() {
        LibraryEntity musicLib = LibraryEntity.builder().libraryType(LibraryType.MUSIC).build();
        DirectoryEntity musicDir = DirectoryEntity.builder().path("/music").libraryEntity(musicLib).build();

        assertTrue(subject.analyzable(Path.of("/music/The Beatles/background.jpg"), true, 0, musicDir));
        assertFalse(subject.analyzable(Path.of("/music/The Beatles/artist.nfo"), true, 0, musicDir));
    }

    @Test
    void analyzableWithDirectoryEntityNonMusicLibraryDelegatesToBase() {
        LibraryEntity videoLib = LibraryEntity.builder().libraryType(LibraryType.SHOW).build();
        DirectoryEntity videoDir = DirectoryEntity.builder().path("/shows").libraryEntity(videoLib).build();

        assertTrue(subject.analyzable(Path.of("/shows/Show (2024)/background.jpg"), true, 0, videoDir));
        assertFalse(subject.analyzable(Path.of("/shows/Show (2024)/s01e01.mkv"), true, 0, videoDir));
    }

    @Test
    void analyzeReturnsEmptyWhenImageAlreadyExists() {
        when(imageRepository.findByDirectoryEntityAndPath(any(), any()))
                .thenReturn(Optional.of(ImageEntity.builder().build()));

        var result = subject.analyze(
                DirectoryEntity.builder().nodeEntity(NodeEntity.builder().name("disk1").build()).build(),
                Path.of("/disk/show/Show (2024)/background.jpg"), false, 0);

        assertTrue(result.isEmpty());
    }

    @Test
    void analyzeCoverImageType() {
        ImageEntity result = (ImageEntity) subject.analyze(
                DirectoryEntity.builder().nodeEntity(NodeEntity.builder().name("disk1").build()).build(),
                Path.of("/disk/show/Show (2024)/cover.png"), false, 0).orElseThrow();
        assertEquals(ImageType.COVER, result.getType());
    }

    @Test
    void analyzeMovieImage() {
        ImageEntity result = (ImageEntity) subject.analyze(
                DirectoryEntity.builder().nodeEntity(NodeEntity.builder().name("disk1").build()).build(),
                Path.of("/disk/movies/Movie (2024)-thumb.jpg"), false, 0).orElseThrow();
        assertEquals(ImageType.BACKGROUND, result.getType());
        verify(scannerHelperService).getOrCreateMovie(any(), any(), anyInt());
    }

    @Test
    void analyzeMusicArtistImageLinksToArtist() {
        LibraryEntity musicLib = LibraryEntity.builder().libraryType(LibraryType.MUSIC).build();
        DirectoryEntity musicDir = DirectoryEntity.builder()
                .path("/music")
                .libraryEntity(musicLib)
                .nodeEntity(NodeEntity.builder().name("disk1").build())
                .build();

        subject.analyze(musicDir, Path.of("/music/The Beatles/background.jpg"), false, 0);

        verify(scannerHelperService).getOrCreatePerson(any(), eq("The Beatles"), anyInt());
    }

    @Test
    void analyzeMusicImageDoesNotCreateOrphanShowOrMovie() {
        LibraryEntity musicLib = LibraryEntity.builder().libraryType(LibraryType.MUSIC).build();
        DirectoryEntity musicDir = DirectoryEntity.builder()
                .path("/music")
                .libraryEntity(musicLib)
                .nodeEntity(NodeEntity.builder().name("disk1").build())
                .build();

        // A compilation folder whose name the video path parser would classify as a show.
        subject.analyze(musicDir, Path.of("/music/Various Artists/Qmusic Top 500 (2017)/cover.jpg"), false, 0);

        verify(scannerHelperService, never()).getOrCreateShow(any(), any(), anyInt());
        verify(scannerHelperService, never()).getOrCreateMovie(any(), any(), anyInt());
        verify(scannerHelperService, never()).getOrCreateEpisode(any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void analyzeMusicAlbumImageLinksToAlbum() {
        LibraryEntity musicLib = LibraryEntity.builder().libraryType(LibraryType.MUSIC).build();
        DirectoryEntity musicDir = DirectoryEntity.builder()
                .path("/music")
                .libraryEntity(musicLib)
                .nodeEntity(NodeEntity.builder().name("disk1").build())
                .build();

        subject.analyze(musicDir, Path.of("/music/The Beatles/Abbey Road (1969)/cover.jpg"), false, 0);

        verify(scannerHelperService).getOrCreateAlbum(any(), any(), eq("Abbey Road"), eq(1969));
    }

    @Test
    void analyzeMusicFlatAlbumStructureReadsAlbumArtistFallbackOnMissingDir() {
        // "/music/Grease_ Soundtrack (1991)" doesn't exist → IOException → returns fallback artist name
        LibraryEntity musicLib = LibraryEntity.builder().libraryType(LibraryType.MUSIC).build();
        DirectoryEntity musicDir = DirectoryEntity.builder()
                .path("/music")
                .libraryEntity(musicLib)
                .nodeEntity(NodeEntity.builder().name("disk1").build())
                .build();

        subject.analyze(musicDir, Path.of("/music/Grease_ Soundtrack (1991)/cover.jpg"), false, 0);

        verify(scannerHelperService).getOrCreateAlbum(any(), any(), eq("Grease_ Soundtrack"), eq(1991));
    }

    @Test
    void readAlbumArtistFromDirectoryReturnsFallbackWhenNoAudioFiles() throws IOException {
        Path albumDir = Files.createDirectories(tempDir.resolve("Soundtrack (2024)"));
        Files.createFile(albumDir.resolve("cover.jpg")); // not an audio file

        LibraryEntity musicLib = LibraryEntity.builder().libraryType(LibraryType.MUSIC).build();
        DirectoryEntity musicDir = DirectoryEntity.builder()
                .path(tempDir.toString())
                .libraryEntity(musicLib)
                .nodeEntity(NodeEntity.builder().name("disk1").build())
                .build();

        subject.analyze(musicDir, albumDir.resolve("cover.jpg"), false, 0);

        // No audio files → fallback artist name "Soundtrack" is used
        verify(scannerHelperService).getOrCreateAlbum(any(), any(), eq("Soundtrack"), eq(2024));
    }

    @Test
    void readAlbumArtistFromDirectoryUsesAlbumArtistTag() throws IOException {
        Path albumDir = Files.createDirectories(tempDir.resolve("Soundtrack (2024)"));
        Files.createFile(albumDir.resolve("01-track.mp3"));

        FFprobe ffprobeMock = mock(FFprobe.class, RETURNS_SELF);
        FFprobeResult probeResult = mock(FFprobeResult.class);
        Format format = mock(Format.class);
        when(jaffree.getFFPROBE()).thenReturn(ffprobeMock);
        when(ffprobeMock.execute()).thenReturn(probeResult);
        when(probeResult.getFormat()).thenReturn(format);
        when(format.getTag("album_artist")).thenReturn("Various Artists");

        LibraryEntity musicLib = LibraryEntity.builder().libraryType(LibraryType.MUSIC).build();
        DirectoryEntity musicDir = DirectoryEntity.builder()
                .path(tempDir.toString())
                .libraryEntity(musicLib)
                .nodeEntity(NodeEntity.builder().name("disk1").build())
                .build();

        subject.analyze(musicDir, albumDir.resolve("cover.jpg"), false, 0);

        verify(scannerHelperService).getOrCreatePerson(any(), eq("Various Artists"), anyInt());
    }

    @Test
    void readAlbumArtistFromDirectoryReturnsFallbackWhenJaffreeThrows() throws IOException {
        Path albumDir = Files.createDirectories(tempDir.resolve("Soundtrack (2024)"));
        Files.createFile(albumDir.resolve("01-track.mp3"));

        FFprobe ffprobeMock = mock(FFprobe.class, RETURNS_SELF);
        when(jaffree.getFFPROBE()).thenReturn(ffprobeMock);
        when(ffprobeMock.execute()).thenThrow(new RuntimeException("ffprobe failed"));

        LibraryEntity musicLib = LibraryEntity.builder().libraryType(LibraryType.MUSIC).build();
        DirectoryEntity musicDir = DirectoryEntity.builder()
                .path(tempDir.toString())
                .libraryEntity(musicLib)
                .nodeEntity(NodeEntity.builder().name("disk1").build())
                .build();

        subject.analyze(musicDir, albumDir.resolve("cover.jpg"), false, 0);

        // Exception → fallback artist name used
        verify(scannerHelperService).getOrCreateAlbum(any(), any(), eq("Soundtrack"), eq(2024));
    }
}
