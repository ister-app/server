package app.ister.server.repository;

import app.ister.server.entitiy.PlayQueueItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PlayQueueItemRepository extends JpaRepository<PlayQueueItemEntity, UUID> {
}
