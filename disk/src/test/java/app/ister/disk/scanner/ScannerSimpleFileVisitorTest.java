package app.ister.disk.scanner;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.service.MessageSender;
import app.ister.disk.scanner.scanners.AudioScanner;
import app.ister.disk.scanner.scanners.ComicScanner;
import app.ister.disk.scanner.scanners.EpubScanner;
import app.ister.disk.scanner.scanners.ImageScanner;
import app.ister.disk.scanner.scanners.MediaFileScanner;
import app.ister.disk.scanner.scanners.NfoScanner;
import app.ister.disk.scanner.scanners.SubtitleScanner;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScannerSimpleFileVisitorTest {
    @Mock
    private ScannedCache scannedCache;
    @Mock
    private MessageSender messageSender;
    @Mock
    private MediaFileScanner mediaFileScanner;
    @Mock
    private ImageScanner imageScanner;
    @Mock
    private NfoScanner nfoScanner;
    @Mock
    private SubtitleScanner subtitleScanner;
    @Mock
    private AudioScanner audioScanner;
    @Mock
    private EpubScanner epubScanner;

    @Mock
    private ComicScanner comicScanner;

    @Mock
    private BasicFileAttributes basicFileAttributes;

    private FileSystem fileSystem;
    private DirectoryEntity directoryEntity;

    @BeforeEach
    void setUp() throws IOException {
        fileSystem = Jimfs.newFileSystem(Configuration.unix());
        Files.createDirectories(fileSystem.getPath("/disk/show/Show (2024)/Season 01"));
        Files.createDirectories(fileSystem.getPath("/disk/show/.tmp/dir"));
        Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/cover.jpg"));
        Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/tvshow.nfo"));
        Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/cover.jpg"));
        Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e01.mkv"));
        Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e01.en.srt"));
        Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e01.jpg"));
        Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e02.mkv"));

        directoryEntity = DirectoryEntity.builder()
                .nodeEntity(NodeEntity.builder().name("TestServer").build())
                .libraryEntity(LibraryEntity.builder().build())
                .name("disk1")
                .path("/disk/show")
                .directoryType(DirectoryType.LIBRARY).build();
    }

    @AfterEach
    void tearDown() throws IOException {
        fileSystem.close();
    }

    @Test
    void visitFile() {
        Path path = Path.of("/path");

        when(mediaFileScanner.analyzable(path, false, 0)).thenReturn(false);
        when(imageScanner.analyzable(path, false, 0)).thenReturn(false);
        when(nfoScanner.analyzable(path, false, 0)).thenReturn(false);
        when(subtitleScanner.analyzable(path, false, 0)).thenReturn(true);

        var subject = new AnalyzerSimpleFileVisitor(directoryEntity, scannedCache, messageSender, new Scanners(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner, audioScanner, epubScanner, comicScanner));

        assertEquals(FileVisitResult.CONTINUE, subject.visitFile(path, basicFileAttributes));

        verify(messageSender).sendFileScanRequested(any(FileScanRequestedData.class), any(String.class));
    }

    @Test
    void visitFileSkipsAlreadyScannedPath() {
        Path path = Path.of("/path");

        when(mediaFileScanner.analyzable(path, false, 0)).thenReturn(true);
        when(imageScanner.analyzable(path, false, 0)).thenReturn(false);
        when(nfoScanner.analyzable(path, false, 0)).thenReturn(false);
        when(subtitleScanner.analyzable(path, false, 0)).thenReturn(false);
        when(scannedCache.foundPath(path.toString())).thenReturn(true);

        var subject = visitor(directoryEntity);

        assertEquals(FileVisitResult.CONTINUE, subject.visitFile(path, basicFileAttributes));

        verify(messageSender, never()).sendFileScanRequested(any(FileScanRequestedData.class), any(String.class));
    }

    @Test
    void visitFileInMusicLibraryUsesDirectoryScopedAnalyzable() {
        Path path = fileSystem.getPath("/disk/music/Artist/Album (2024)/01 - Track.flac");
        DirectoryEntity musicDir = libraryDirectory(LibraryType.MUSIC, "/disk/music");

        when(audioScanner.analyzable(path, false, musicDir)).thenReturn(true);
        when(imageScanner.analyzable(path, false, 0, musicDir)).thenReturn(false);
        when(nfoScanner.analyzable(path, false, 0, musicDir)).thenReturn(false);
        when(scannedCache.foundMusicAudioPath(path.toString())).thenReturn(false);

        var subject = visitor(musicDir);

        assertEquals(FileVisitResult.CONTINUE, subject.visitFile(path, basicFileAttributes));

        verify(messageSender).sendFileScanRequested(any(FileScanRequestedData.class), any(String.class));
    }

    @Test
    void visitFileInBookLibraryUsesEpubScanner() {
        Path path = fileSystem.getPath("/disk/books/Author/Book.epub");
        DirectoryEntity bookDir = libraryDirectory(LibraryType.BOOK, "/disk/books");

        when(epubScanner.analyzable(path, false, bookDir)).thenReturn(true);
        when(audioScanner.analyzable(path, false, bookDir)).thenReturn(false);
        when(imageScanner.analyzable(path, false, 0, bookDir)).thenReturn(false);
        when(nfoScanner.analyzable(path, false, 0, bookDir)).thenReturn(false);
        when(scannedCache.foundPath(path.toString())).thenReturn(false);

        var subject = visitor(bookDir);

        assertEquals(FileVisitResult.CONTINUE, subject.visitFile(path, basicFileAttributes));

        verify(messageSender).sendFileScanRequested(any(FileScanRequestedData.class), any(String.class));
    }

    @Test
    void visitFileFailedContinues() {
        var subject = visitor(directoryEntity);
        assertEquals(FileVisitResult.CONTINUE,
                subject.visitFileFailed(Path.of("/path"), new IOException("unreadable")));
    }

    @Test
    void postVisitDirectoryContinues() {
        var subject = visitor(directoryEntity);
        assertEquals(FileVisitResult.CONTINUE, subject.postVisitDirectory(Path.of("/path"), null));
    }

    private AnalyzerSimpleFileVisitor visitor(DirectoryEntity directory) {
        return new AnalyzerSimpleFileVisitor(directory, scannedCache, messageSender,
                new Scanners(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner, audioScanner, epubScanner, comicScanner));
    }

    private DirectoryEntity libraryDirectory(LibraryType libraryType, String path) {
        return DirectoryEntity.builder()
                .nodeEntity(NodeEntity.builder().name("TestServer").build())
                .libraryEntity(LibraryEntity.builder().libraryType(libraryType).name(libraryType.name()).build())
                .name("disk1")
                .path(path)
                .directoryType(DirectoryType.LIBRARY).build();
    }

    @Nested
    class PreVisitDirectory {
        @Test
        void showDirWillBeAnalyzedAndContinued() {
            when(basicFileAttributes.isDirectory()).thenReturn(true);
            Path path = fileSystem.getPath("/disk/show/Show (2024)");

            var result = visitor(directoryEntity).preVisitDirectory(path, basicFileAttributes);

            assertEquals(FileVisitResult.CONTINUE, result);
        }

        @Test
        void nonDirectoryWillBeSkipped() {
            when(basicFileAttributes.isDirectory()).thenReturn(false);
            Path path = fileSystem.getPath("/disk/show/Show (2024)");

            var result = visitor(directoryEntity).preVisitDirectory(path, basicFileAttributes);

            assertEquals(FileVisitResult.SKIP_SUBTREE, result);
        }

        @Test
        void musicArtistDirWillBeContinued() {
            when(basicFileAttributes.isDirectory()).thenReturn(true);
            DirectoryEntity musicDir = libraryDirectory(LibraryType.MUSIC, "/disk/music");
            Path path = fileSystem.getPath("/disk/music/Artist");

            var result = visitor(musicDir).preVisitDirectory(path, basicFileAttributes);

            assertEquals(FileVisitResult.CONTINUE, result);
        }

        @Test
        void bookAuthorDirWillBeContinued() {
            when(basicFileAttributes.isDirectory()).thenReturn(true);
            DirectoryEntity bookDir = libraryDirectory(LibraryType.BOOK, "/disk/books");
            Path path = fileSystem.getPath("/disk/books/Author");

            var result = visitor(bookDir).preVisitDirectory(path, basicFileAttributes);

            assertEquals(FileVisitResult.CONTINUE, result);
        }

        @Test
        void bookDirOutsideLibraryRootWillBeSkipped() {
            when(basicFileAttributes.isDirectory()).thenReturn(true);
            DirectoryEntity bookDir = libraryDirectory(LibraryType.BOOK, "/disk/books");
            Path path = fileSystem.getPath("/elsewhere/Author");

            var result = visitor(bookDir).preVisitDirectory(path, basicFileAttributes);

            assertEquals(FileVisitResult.SKIP_SUBTREE, result);
        }
    }

    @Nested
    class PreVisitDirectoryBasics {
        @Test
        void theRootDirWillReturnContinue() {
            Path resourceFilePath = fileSystem.getPath("/disk/show");
            var subject = new AnalyzerSimpleFileVisitor(directoryEntity, scannedCache, messageSender, new Scanners(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner, audioScanner, epubScanner, comicScanner));

            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);

            assertEquals(FileVisitResult.CONTINUE, result);
        }

        @Test
        void dotDirsWillBeSkipped() {
            Path resourceFilePath = fileSystem.getPath("/disk/show/.tmp");
            var subject = new AnalyzerSimpleFileVisitor(directoryEntity, scannedCache, messageSender, new Scanners(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner, audioScanner, epubScanner, comicScanner));

            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);

            assertEquals(FileVisitResult.SKIP_SUBTREE, result);
        }
    }
}
