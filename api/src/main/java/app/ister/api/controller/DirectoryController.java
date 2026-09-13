package app.ister.api.controller;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.StorageKind;
import app.ister.core.repository.DirectoryRepository;
import lombok.RequiredArgsConstructor;

import java.util.List;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class DirectoryController {

    private final DirectoryRepository directoryRepository;

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

    @SchemaMapping(typeName = "Directory", field = "storageKind")
    public StorageKind storageKind(DirectoryEntity directoryEntity) {
        return directoryEntity.getStorageKind() == null ? StorageKind.LOCAL : directoryEntity.getStorageKind();
    }

    @SchemaMapping(typeName = "Directory", field = "attachedNodes")
    public List<NodeEntity> attachedNodes(DirectoryEntity directoryEntity) {
        return directoryRepository.findAttachedNodes(directoryEntity.getId());
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
