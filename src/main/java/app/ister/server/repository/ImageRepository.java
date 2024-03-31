package app.ister.server.repository;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.ImageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface ImageRepository extends CrudRepository<ImageEntity, UUID> {
    Page<ImageEntity> findAll(Pageable pageable);

    Optional<ImageEntity> findByDirectoryEntityAndPath(DirectoryEntity directoryEntity, String path);
}