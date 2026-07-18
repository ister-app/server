package app.ister.core.repository;

import app.ister.core.entity.PlayQueueControlGrantEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlayQueueControlGrantRepository extends CrudRepository<PlayQueueControlGrantEntity, UUID> {

    List<PlayQueueControlGrantEntity> findByPlayQueueEntityId(UUID playQueueEntityId);

    void deleteByPlayQueueEntityId(UUID playQueueEntityId);

    /** Grantee ids for one session's control allowlist, as a projection (no association navigated). */
    @Query("select g.granteeEntity.id from PlayQueueControlGrantEntity g "
            + "where g.playQueueEntity.id = :playQueueId")
    List<UUID> findGranteeIdsByPlayQueueId(@Param("playQueueId") UUID playQueueId);
}
