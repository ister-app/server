package app.ister.server.scanner;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.OtherPathFileEntity;
import app.ister.server.repository.ImageRepository;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.OtherPathFileRepository;

import java.util.List;

public class ScannedCache {
    private final List<ImageEntity> imageEntities;
    private final List<MediaFileEntity> mediaFileEntities;
    private final List<OtherPathFileEntity> otherPathFileEntities;

    private ImageRepository imageRepository;
    private MediaFileRepository mediaFileRepository;
    private OtherPathFileRepository otherPathFileRepository;

    public ScannedCache(DirectoryEntity directoryEntity,
                        ImageRepository imageRepository,
                        MediaFileRepository mediaFileRepository,
                        OtherPathFileRepository otherPathFileRepository) {
        this.imageRepository = imageRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.otherPathFileRepository = otherPathFileRepository;

        imageEntities = imageRepository.findByDirectoryEntity(directoryEntity);
        mediaFileEntities = mediaFileRepository.findByDirectoryEntity(directoryEntity);
        otherPathFileEntities = otherPathFileRepository.findByDirectoryEntity(directoryEntity);
    }

    public boolean foundPath(String path) {
        return (imageEntities.removeIf(imageEntity -> imageEntity.getPath().equals(path)) ||
                mediaFileEntities.removeIf(mediaFileEntity -> mediaFileEntity.getPath().equals(path)) ||
                otherPathFileEntities.removeIf(otherPathFileEntity -> otherPathFileEntity.getPath().equals(path)));
    }

    public void removeNotScannedFilesFromDatabase() {
        imageRepository.deleteAll(imageEntities);
        mediaFileRepository.deleteAll(mediaFileEntities);
        otherPathFileRepository.deleteAll(otherPathFileEntities);
    }
}
