package app.ister.disk.scanner;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.OtherPathFileEntity;
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

    /**
     * Like foundPath, but for music audio files: marks the path as seen (so it won't be deleted)
     * yet returns false when the existing entry has no track entity, allowing the scanner to
     * re-process and assign the correct track.
     */
    public boolean foundMusicAudioPath(String path) {
        boolean[] hasTrack = {false};
        boolean removed = mediaFileEntities.removeIf(mf -> {
            if (mf.getPath().equals(path)) {
                hasTrack[0] = mf.getTrackEntity() != null;
                return true;
            }
            return false;
        });
        return removed && hasTrack[0];
    }

    public void removeNotScannedFilesFromDatabase() {
        imageRepository.deleteAll(imageEntities);
        mediaFileRepository.deleteAll(mediaFileEntities);
        otherPathFileRepository.deleteAll(otherPathFileEntities);
    }
}
