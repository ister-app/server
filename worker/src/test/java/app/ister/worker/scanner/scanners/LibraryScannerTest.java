package app.ister.worker.scanner.scanners;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.LibraryEntity;
import app.ister.core.entitiy.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.worker.scanner.LibraryScanner;
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
    @InjectMocks
    LibraryScanner libraryScanner;
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
    private ImageRepository imageRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private OtherPathFileRepository otherPathFileRepository;

    @Test
    void simpleTest() throws IOException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
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