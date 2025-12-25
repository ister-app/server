package app.ister.core.repository;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.ImageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImageRepository extends CrudRepository<ImageEntity, UUID> {
    Page<ImageEntity> findAll(Pageable pageable);

    Optional<ImageEntity> findByDirectoryEntityAndPath(DirectoryEntity directoryEntity, String path);

    Optional<ImageEntity> findByDirectoryEntityIdAndPath(UUID directoryEntityId, String path);

    List<ImageEntity> findByDirectoryEntity(DirectoryEntity directoryEntity);

    List<ImageEntity> findByShowEntityId(UUID showEntityId);
}