package app.ister.core.repository;

import app.ister.core.entity.GameAchievementEntity;
import app.ister.core.enums.GameAchievementSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GameAchievementRepository extends JpaRepository<GameAchievementEntity, UUID> {

    List<GameAchievementEntity> findByGameEntityIdOrderBySortOrderAscNameAsc(UUID gameId);

    List<GameAchievementEntity> findByGameEntityIdAndSource(UUID gameId, GameAchievementSource source);

    List<GameAchievementEntity> findByGameEntityIdIn(Collection<UUID> gameIds);

    long countByGameEntityId(UUID gameId);
}
