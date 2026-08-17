package app.ister.core.repository;

import app.ister.core.entity.MediaFileSegmentEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MediaFileSegmentRepository extends CrudRepository<MediaFileSegmentEntity, UUID> {

    /** Advisory-lock namespace, so the season keys can never collide with another lock user. */
    int SEGMENT_DETECTION_LOCK_NAMESPACE = 1;

    List<MediaFileSegmentEntity> findByMediaFileEntityId(UUID mediaFileEntityId);

    List<MediaFileSegmentEntity> findByMediaFileEntityIdIn(Collection<UUID> mediaFileEntityIds);

    void deleteAllByMediaFileEntityId(UUID mediaFileEntityId);

    /**
     * Tries to claim a season for segment detection, without blocking: true when this transaction
     * got the lock, false when another one is already detecting that season. Held until the
     * transaction ends, so callers must run inside one.
     *
     * <p>Non-blocking on purpose. Detection fingerprints audio for minutes, and a waiting consumer
     * would both idle a listener thread and risk the broker's consumer timeout; a caller that does
     * not get the lock has nothing useful to do anyway, because the holder's chunk chain covers the
     * whole season.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:namespace, hashtext(CAST(:seasonId AS text)))",
            nativeQuery = true)
    boolean tryLockSeason(@Param("namespace") int namespace, @Param("seasonId") UUID seasonId);
}
