package app.ister.server.scanner;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.LibraryEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.events.filescanrequested.FileScanRequestedData;
import app.ister.server.scanner.scanners.ImageScanner;
import app.ister.server.scanner.scanners.MediaFileScanner;
import app.ister.server.scanner.scanners.NfoScanner;
import app.ister.server.scanner.scanners.SubtitleScanner;
import app.ister.server.service.MessageSender;
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

        var subject = new AnalyzerSimpleFileVisitor(directoryEntity, scannedCache, messageSender, mediaFileScanner, imageScanner, nfoScanner, subtitleScanner);

        assertEquals(FileVisitResult.CONTINUE, subject.visitFile(path, basicFileAttributes));

        verify(messageSender).sendFileScanRequested(any(FileScanRequestedData.class));
    }

    @Nested
    class PreVisitDirectory {
        @Test
        void theRootDirWillReturnContinue() {
            Path resourceFilePath = fileSystem.getPath("/disk/show");
            var subject = new AnalyzerSimpleFileVisitor(directoryEntity, scannedCache, messageSender, mediaFileScanner, imageScanner, nfoScanner, subtitleScanner);

            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);

            assertEquals(result, FileVisitResult.CONTINUE);
        }

        @Test
        void dotDirsWillBeSkipped() {
            Path resourceFilePath = fileSystem.getPath("/disk/show/.tmp");
            var subject = new AnalyzerSimpleFileVisitor(directoryEntity, scannedCache, messageSender, mediaFileScanner, imageScanner, nfoScanner, subtitleScanner);

            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);

            assertEquals(result, FileVisitResult.SKIP_SUBTREE);
        }
    }
}