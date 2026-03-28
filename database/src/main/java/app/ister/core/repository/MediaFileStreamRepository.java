package app.ister.core.repository;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileStreamRepository extends CrudRepository<MediaFileStreamEntity, UUID> {

    Optional<MediaFileStreamEntity> findByMediaFileEntity(MediaFileEntity mediaFileEntity);

    boolean existsByMediaFileEntityAndStreamIndexAndPath(MediaFileEntity mediaFileEntity, int streamIndex, String path);

    List<MediaFileStreamEntity> findByMediaFileEntity_IdAndCodecType(UUID mediaFileId, StreamCodecType codecType);

    void deleteAllByMediaFileEntityId(UUID mediaFileEntityId);
}
