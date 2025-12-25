package app.ister.core.repository;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.MediaFileEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileRepository extends CrudRepository<MediaFileEntity, UUID> {

    Optional<MediaFileEntity> findByDirectoryEntityAndPath(DirectoryEntity directoryEntity, String path);

    List<MediaFileEntity> findByDirectoryEntity(DirectoryEntity directoryEntity);
}
