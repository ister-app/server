package app.ister.core.repository;

import app.ister.core.entitiy.PlayQueueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PlayQueueRepository extends JpaRepository<PlayQueueEntity, UUID> {
}
