package app.ister.server.scanner;

import app.ister.server.entitiy.CategorieEntity;
import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.enums.DiskType;
import app.ister.server.scanner.scanners.*;
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

@ExtendWith(MockitoExtension.class)
class ScannerSimpleFileVisitorTest {
    @Mock
    private ShowScanner showAnalyzer;
    @Mock
    private SeasonScanner seasonAnalyzer;
    @Mock
    private MediaFileScanner episodeAnalyzer;
    @Mock
    private ImageScanner imageAnalyzer;
    @Mock
    private NfoScanner nfoScanner;
    @Mock
    private SubtitleScanner subtitleScanner;

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

        diskEntity = DiskEntity.builder()
                .nodeEntity(NodeEntity.builder().name("TestServer").build())
                .categorieEntity(CategorieEntity.builder().build())
                .path("/disk/show")
                .diskType(DiskType.LIBRARY).build();
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
            var subject = new AnalyzerSimpleFileVisitor(diskEntity, showAnalyzer, seasonAnalyzer, episodeAnalyzer, imageAnalyzer, nfoScanner, subtitleScanner);

            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);

            assertEquals(result, FileVisitResult.CONTINUE);
        }

        @Test
        void dotDirsWillBeSkipped() {
            Path resourceFilePath = fileSystem.getPath("/disk/show/.tmp");
            var subject = new AnalyzerSimpleFileVisitor(diskEntity, showAnalyzer, seasonAnalyzer, episodeAnalyzer, imageAnalyzer, nfoScanner, subtitleScanner);

            var result = subject.preVisitDirectory(resourceFilePath, basicFileAttributes);

            assertEquals(result, FileVisitResult.SKIP_SUBTREE);
        }
    }
}