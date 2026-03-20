package app.ister.api.controller;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DirectoryControllerTest {

    @InjectMocks
    private DirectoryController subject;

    @Test
    void directoryForImageReturnsDirectoryFromImage() {
        DirectoryEntity dir = DirectoryEntity.builder().name("dir").path("/dir").directoryType(DirectoryType.LIBRARY).build();
        ImageEntity image = ImageEntity.builder().build();
        image.setDirectoryEntity(dir);

        DirectoryEntity result = subject.directory(image);

        assertEquals(dir, result);
    }

    @Test
    void directoryForMediaFileReturnsDirectoryFromMediaFile() {
        DirectoryEntity dir = DirectoryEntity.builder().name("dir").path("/dir").directoryType(DirectoryType.LIBRARY).build();
        MediaFileEntity mediaFile = MediaFileEntity.builder().directoryEntity(dir).build();

        DirectoryEntity result = subject.directory(mediaFile);

        assertEquals(dir, result);
    }

    @Test
    void nodeReturnsNodeFromDirectory() {
        NodeEntity node = NodeEntity.builder().name("node1").url("http://node1").build();
        DirectoryEntity dir = DirectoryEntity.builder().name("dir").path("/dir").directoryType(DirectoryType.LIBRARY).nodeEntity(node).build();

        NodeEntity result = subject.node(dir);

        assertEquals(node, result);
    }

    @Test
    void libraryReturnsLibraryFromDirectory() {
        LibraryEntity library = LibraryEntity.builder().name("Movies").libraryType(LibraryType.MOVIE).build();
        DirectoryEntity dir = DirectoryEntity.builder().name("dir").path("/dir").directoryType(DirectoryType.LIBRARY).libraryEntity(library).build();

        LibraryEntity result = subject.library(dir);

        assertEquals(library, result);
    }

    @Test
    void typeReturnsDirectoryType() {
        DirectoryEntity dir = DirectoryEntity.builder().name("dir").path("/dir").directoryType(DirectoryType.CACHE).build();

        DirectoryType result = subject.type(dir);

        assertEquals(DirectoryType.CACHE, result);
    }

    @Test
    void typeReturnsLibraryType() {
        LibraryEntity library = LibraryEntity.builder().name("Shows").libraryType(LibraryType.SHOW).build();

        LibraryType result = subject.type(library);

        assertEquals(LibraryType.SHOW, result);
    }
}
