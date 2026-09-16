package app.ister.core.repository;

import app.ister.core.entity.PlayQueueItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface PlayQueueItemRepository extends JpaRepository<PlayQueueItemEntity, UUID> {
    java.util.List<PlayQueueItemEntity> findByTrackEntityId(UUID trackEntityId);


    @Modifying
    @Query("UPDATE PlayQueueItemEntity p SET p.movieEntityId = :target WHERE p.movieEntityId = :source")
    int moveMovie(@Param("source") UUID source, @Param("target") UUID target);
}
