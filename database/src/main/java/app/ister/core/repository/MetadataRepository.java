package app.ister.core.repository;

import app.ister.core.entity.MetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MetadataRepository extends JpaRepository<MetadataEntity, UUID> {

    List<MetadataEntity> findByPersonEntityId(UUID personId);

    List<MetadataEntity> findByAlbumEntityId(UUID albumId);
}
