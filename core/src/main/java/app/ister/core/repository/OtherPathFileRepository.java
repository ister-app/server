package app.ister.core.repository;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.OtherPathFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtherPathFileRepository extends JpaRepository<OtherPathFileEntity, UUID> {
    Optional<OtherPathFileEntity> findByDirectoryEntityAndPath(DirectoryEntity directoryEntity, String path);

    List<OtherPathFileEntity> findByDirectoryEntity(DirectoryEntity directoryEntity);
}
