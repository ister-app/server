package app.ister.server.repository;

import app.ister.server.entitiy.ServerEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ServerEventRepository extends JpaRepository<ServerEventEntity, UUID> {
}
