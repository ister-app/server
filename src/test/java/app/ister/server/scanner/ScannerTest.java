package app.ister.server.scanner;

import app.ister.server.entitiy.CategorieEntity;
import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.enums.DiskType;
import app.ister.server.repository.*;
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
class ScannerTest {

    @Mock
    private ShowAnalyzer showAnalyzer;
    @Mock
    private SeasonAnalyzer seasonAnalyzer;
    @Mock
    private EpisodeAnalyzer episodeAnalyzer;
    @Mock
    private MediaFileAnalyzer mediaFileAnalyzer;

    @InjectMocks
    Scanner scanner;


    @Test
    void simpleTest() throws IOException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());) {
            Path resourceFilePath = fileSystem.getPath("/disk/show");

            Files.createDirectories(resourceFilePath);
            Files.createDirectories(fileSystem.getPath("/disk/show/Show (2024)/Season 01"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/background.jpg"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/cover.jpg"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/show.nfo"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/cover.jpg"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e01.mkv"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e01.en.srt"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e01.jpg"));
            Files.createFile(fileSystem.getPath("/disk/show/Show (2024)/Season 01/s01e02.mkv"));

            DiskEntity diskEntity = new DiskEntity(new NodeEntity("TestServer"), new CategorieEntity(), "/disk/show", DiskType.LIBRARY);

            scanner.scanDiskForCategorie(resourceFilePath, diskEntity);
            assertTrue(true);
        }
    }
}