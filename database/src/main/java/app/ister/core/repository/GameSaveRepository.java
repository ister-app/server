package app.ister.core.repository;

import app.ister.core.entity.GameSaveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameSaveRepository extends JpaRepository<GameSaveEntity, UUID> {

    List<GameSaveEntity> findByUserEntityIdAndGameEntityIdOrderByVersionDesc(UUID userId, UUID gameId);

    Optional<GameSaveEntity> findFirstByUserEntityIdAndGameEntityIdOrderByVersionDesc(UUID userId, UUID gameId);

    Optional<GameSaveEntity> findByUserEntityIdAndGameEntityIdAndVersion(UUID userId, UUID gameId, int version);

    List<GameSaveEntity> findByGameEntityId(UUID gameId);

    /** Saves of a user across games, newest first — the "my saves" overview. */
    List<GameSaveEntity> findByUserEntityIdOrderByDateCreatedDesc(UUID userId);

    /** Every stored zip on a cache directory: what the cache cleanup must keep. */
    @Query("select s.storedPath from GameSaveEntity s where s.cacheDirectoryEntity.id = :cacheDirectoryId")
    List<String> findStoredPathsByCacheDirectoryEntityId(@Param("cacheDirectoryId") UUID cacheDirectoryId);
}
