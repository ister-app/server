package app.ister.api.controller;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
public class DirectoryController {

    @SchemaMapping(typeName = "Image", field = "directory")
    public DirectoryEntity directory(ImageEntity imageEntity) {
        return imageEntity.getDirectoryEntity();
    }

    @SchemaMapping(typeName = "MediaFile", field = "directory")
    public DirectoryEntity directory(MediaFileEntity mediaFileEntity) {
        return mediaFileEntity.getDirectoryEntity();
    }

    @SchemaMapping(typeName = "Directory", field = "node")
    public NodeEntity node(DirectoryEntity directoryEntity) {
        return directoryEntity.getNodeEntity();
    }

    @SchemaMapping(typeName = "Directory", field = "library")
    public LibraryEntity library(DirectoryEntity directoryEntity) {
        return directoryEntity.getLibraryEntity();
    }

    @SchemaMapping(typeName = "Directory", field = "type")
    public DirectoryType type(DirectoryEntity directoryEntity) {
        return directoryEntity.getDirectoryType();
    }

    @SchemaMapping(typeName = "Library", field = "type")
    public LibraryType type(LibraryEntity libraryEntity) {
        return libraryEntity.getLibraryType();
    }
}
