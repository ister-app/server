package app.ister.server.repository;

import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface MediaFileStreamRepository extends CrudRepository<MediaFileStreamEntity, UUID> {

    Optional<MediaFileEntity> findByMediaFileEntity(MediaFileEntity mediaFileEntity);
}
