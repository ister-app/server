package app.ister.server.scanner;

import app.ister.server.entitiy.*;
import app.ister.server.enums.DiskType;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class ScannerSimpleFileVisitorTest {
    @Mock
    private ShowScanner showAnalyzer;
    @Mock
    private SeasonScanner seasonAnalyzer;
    @Mock
    private EpisodeScanner episodeAnalyzer;
    @Mock
    private ImageScanner imageAnalyzer;
    @Mock
    private NfoScanner nfoScanner;

    @Mock
    private BasicFileAttributes basicFileAttributes;

    private FileSystem fileSystem;
    private DiskEntity diskEntity;

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

        diskEntity = new DiskEntity(new NodeEntity("TestServer"), new CategorieEntity(), "/disk/show", DiskType.LIBRARY);
    }

    @AfterEach
    void tearDown() throws IOException {
        fileSystem.close();
    }


    @Nested
    class PreVisitDirectory {
        @Test
        void theRootDirWillReturnContinue() {
            Path resourceFilePath = fileSystem.getPath("/disk/show");
            var subject = new AnalyzerSimpleFileVisitor(diskEntity, showAnalyzer, seasonAnalyzer, episodeAnalyzer, imageAnalyzer, nfoScanner);

            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);

            assertEquals(result, FileVisitResult.CONTINUE);
        }

        @Test
        void dotDirsWillBeSkipped() {
            Path resourceFilePath = fileSystem.getPath("/disk/show/.tmp");
            var subject = new AnalyzerSimpleFileVisitor(diskEntity, showAnalyzer, seasonAnalyzer, episodeAnalyzer, imageAnalyzer, nfoScanner);

            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);

            assertEquals(result, FileVisitResult.SKIP_SUBTREE);
        }

//        @Test
//        void itScansForShows() {
//            Path resourceFilePath = fileSystem.getPath("/disk/show/Show (2024)");
//            var subject = new AnalyzerSimpleFileVisitor(diskEntity, showAnalyzer, seasonAnalyzer, episodeAnalyzer, mediaFileAnalyzer);
//
//            when(showAnalyzer.get(any(), any())).thenReturn(Optional.of(new ShowEntity()));
//
//            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);
//
//            verify(showAnalyzer).get(any(), any());
//            verify(seasonAnalyzer, never()).get(any(), any());
//
//            assertEquals(result, FileVisitResult.CONTINUE);
//        }
//
//        @Test
//        void itScansForSeasons() {
//            Path resourceFilePath = fileSystem.getPath("/disk/show/Show (2024)");
//            var subject = new AnalyzerSimpleFileVisitor(diskEntity, showAnalyzer, seasonAnalyzer, episodeAnalyzer, mediaFileAnalyzer);
////            subject.inShow = Optional.of(new TVShow());
//
//            when(showAnalyzer.get(any(), any())).thenReturn(Optional.empty());
//            when(seasonAnalyzer.get(any(), any())).thenReturn(Optional.of(new SeasonEntity()));
//
//            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);
//
//            verify(showAnalyzer).get(any(), any());
//            verify(seasonAnalyzer).get(any(), any());
//
//            assertEquals(result, FileVisitResult.CONTINUE);
//        }
//
//        @Test
//        void itDoesntScanSeasonWithoutShow() {
//            Path resourceFilePath = fileSystem.getPath("/disk/show/Show (2024)");
//            var subject = new AnalyzerSimpleFileVisitor(diskEntity, showAnalyzer, seasonAnalyzer, episodeAnalyzer, mediaFileAnalyzer);
//
//            when(showAnalyzer.get(any(), any())).thenReturn(Optional.empty());
//
//            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);
//
//            verify(showAnalyzer).get(any(), any());
//            verify(seasonAnalyzer, never()).get(any(), any());
//
//            assertEquals(result, FileVisitResult.SKIP_SUBTREE);
//        }
    }
}