package app.ister.core.repository;

import app.ister.core.entity.UploadFileEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UploadFileRepository extends CrudRepository<UploadFileEntity, UUID> {
    List<UploadFileEntity> findByUploadSessionIdOrderByTargetPath(UUID uploadSessionId);

    /**
     * Bytes that active uploads into a directory still have to deliver. The free-space check of a
     * new session subtracts it: space another session was promised is not free.
     */
    @Query("""
            SELECT COALESCE(SUM(f.size - f.receivedBytes), 0) FROM UploadFileEntity f
            WHERE f.uploadSession.directoryEntity.id = :directoryId
              AND f.uploadSession.status = app.ister.core.enums.UploadSessionStatus.ACTIVE
              AND f.status IN (app.ister.core.enums.UploadFileStatus.PENDING,
                               app.ister.core.enums.UploadFileStatus.UPLOADING)""")
    long sumRemainingBytesByDirectory(@Param("directoryId") UUID directoryId);

    /** Targets another active upload is already assembling (upload preview and session creation). */
    @Query("""
            SELECT f.targetPath FROM UploadFileEntity f
            WHERE f.targetPath IN :paths
              AND f.status IN (app.ister.core.enums.UploadFileStatus.PENDING,
                               app.ister.core.enums.UploadFileStatus.UPLOADING)""")
    List<String> findActiveTargetPathsIn(@Param("paths") Collection<String> paths);
}
