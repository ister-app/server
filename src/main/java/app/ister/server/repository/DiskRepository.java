package app.ister.server.repository;

import app.ister.server.entitiy.DiskEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

public interface DiskRepository extends CrudRepository<DiskEntity, UUID> {
}
