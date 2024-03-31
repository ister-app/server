package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.LibraryEntity;
import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.scanner.LibraryScanner;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class LibraryScannerTest {

    @Mock
    private ShowScanner showAnalyzer;
    @Mock
    private SeasonScanner seasonAnalyzer;
    @Mock
    private MediaFileScanner episodeAnalyzer;

    @InjectMocks
    LibraryScanner libraryScanner;


    @Test
    void simpleTest() throws IOException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());) {
            Path resourceFilePath = fileSystem.getPath("/disk/show");

            Files.createDirectories(resourceFilePath);
            Files.createDirectories(fileSystem.getPath("/disk/show/Show (2024)/Season 01"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/background.jpg"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/cover.jpg"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/tvshow.nfo"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/cover.jpg"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e01.mkv"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e01.en.srt"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e01.jpg"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e02.mkv"));

            DirectoryEntity directoryEntity = DirectoryEntity.builder()
                    .nodeEntity(NodeEntity.builder().name("TestServer").build())
                    .libraryEntity(LibraryEntity.builder().build())
                    .path("/disk/show")
                    .directoryType(DirectoryType.LIBRARY).build();

            libraryScanner.scanDirectory(resourceFilePath, directoryEntity);
            assertTrue(true);
        }
    }
}