package app.ister.core.repository;

import app.ister.core.entitiy.MediaFileEntity;
import app.ister.core.entitiy.MediaFileStreamEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface MediaFileStreamRepository extends CrudRepository<MediaFileStreamEntity, UUID> {

    Optional<MediaFileEntity> findByMediaFileEntity(MediaFileEntity mediaFileEntity);
}
