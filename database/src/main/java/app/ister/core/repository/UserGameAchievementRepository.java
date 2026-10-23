package app.ister.core.repository;

import app.ister.core.entity.GameAchievementEntity;
import app.ister.core.entity.UserGameAchievementEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserGameAchievementRepository extends JpaRepository<UserGameAchievementEntity, UUID> {

    @Query("""
            select u from UserGameAchievementEntity u
            where u.userEntity.id = :userId and u.gameAchievementEntity.gameEntity.id = :gameId""")
    List<UserGameAchievementEntity> findByUserAndGame(@Param("userId") UUID userId, @Param("gameId") UUID gameId);

    @Query("""
            select u from UserGameAchievementEntity u
            where u.userEntity.id = :userId and u.gameAchievementEntity.gameEntity.id in :gameIds""")
    List<UserGameAchievementEntity> findByUserAndGameIn(@Param("userId") UUID userId,
                                                        @Param("gameIds") Collection<UUID> gameIds);

    @Query("""
            select u from UserGameAchievementEntity u
            join fetch u.gameAchievementEntity a join fetch a.gameEntity
            where u.userEntity.id = :userId order by u.unlockedAt desc""")
    List<UserGameAchievementEntity> findRecentByUser(@Param("userId") UUID userId, Pageable pageable);

    @Query("select count(u) from UserGameAchievementEntity u where u.userEntity.id = :userId and u.gameAchievementEntity.gameEntity.id = :gameId")
    long countByUserAndGame(@Param("userId") UUID userId, @Param("gameId") UUID gameId);

    @Modifying
    @Query("delete from UserGameAchievementEntity u where u.gameAchievementEntity in :definitions")
    void deleteByDefinitions(@Param("definitions") Collection<GameAchievementEntity> definitions);
}
