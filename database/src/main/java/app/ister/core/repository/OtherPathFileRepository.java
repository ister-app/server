package app.ister.core.repository;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.OtherPathFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtherPathFileRepository extends JpaRepository<OtherPathFileEntity, UUID> {
    Optional<OtherPathFileEntity> findByDirectoryEntityAndPath(DirectoryEntity directoryEntity, String path);

    List<OtherPathFileEntity> findByDirectoryEntity(DirectoryEntity directoryEntity);

    /** Which of the given paths are already known in a directory (upload preview: "exists" check). */
    @Query("SELECT o.path FROM OtherPathFileEntity o WHERE o.directoryEntityId = :directoryEntityId AND o.path IN :paths")
    List<String> findPathsByDirectoryEntityIdAndPathIn(@Param("directoryEntityId") UUID directoryEntityId,
                                                       @Param("paths") Collection<String> paths);

    Optional<OtherPathFileEntity> findByMetadataEntity(MetadataEntity metadataEntity);

    Optional<OtherPathFileEntity> findByMediaFileStreamEntity(MediaFileStreamEntity mediaFileStreamEntity);
}
