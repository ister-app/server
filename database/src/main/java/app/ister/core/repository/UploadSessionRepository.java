package app.ister.core.repository;

import app.ister.core.entity.UploadSessionEntity;
import app.ister.core.enums.UploadSessionStatus;
import org.springframework.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UploadSessionRepository extends CrudRepository<UploadSessionEntity, UUID> {
    /** Sessions nobody touched since the cutoff (cleanup sweep). */
    List<UploadSessionEntity> findByStatusAndLastActivityAtBefore(UploadSessionStatus status, Instant cutoff);

    List<UploadSessionEntity> findByStatus(UploadSessionStatus status);

    long countByStatus(UploadSessionStatus status);
}
