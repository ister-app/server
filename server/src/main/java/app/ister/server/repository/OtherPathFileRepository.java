package app.ister.server.repository;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.OtherPathFileEntity;
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
