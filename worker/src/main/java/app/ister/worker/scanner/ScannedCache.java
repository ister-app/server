package app.ister.worker.scanner;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.ImageEntity;
import app.ister.core.entitiy.MediaFileEntity;
import app.ister.core.entitiy.OtherPathFileEntity;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.OtherPathFileRepository;

import java.util.List;

public class ScannedCache {
    private final List<ImageEntity> imageEntities;
    private final List<MediaFileEntity> mediaFileEntities;
    private final List<OtherPathFileEntity> otherPathFileEntities;

    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;
    private final OtherPathFileRepository otherPathFileRepository;

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
