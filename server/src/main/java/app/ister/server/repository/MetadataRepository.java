package app.ister.server.repository;

import app.ister.server.entitiy.MetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MetadataRepository extends JpaRepository<MetadataEntity, UUID> {
}
