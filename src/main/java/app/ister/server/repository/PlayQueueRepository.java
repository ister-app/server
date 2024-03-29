package app.ister.server.repository;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.PlayQueueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PlayQueueRepository extends JpaRepository<PlayQueueEntity, UUID> {
}
