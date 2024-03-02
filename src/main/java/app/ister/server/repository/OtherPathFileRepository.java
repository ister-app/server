package app.ister.server.repository;

import app.ister.server.entitiy.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtherPathFileRepository extends JpaRepository<OtherPathFileEntity, UUID> {
    Optional<OtherPathFileEntity> findByDiskEntityAndPath(DiskEntity diskEntity, String path);
}
