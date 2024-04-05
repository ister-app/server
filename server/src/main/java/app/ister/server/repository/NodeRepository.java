package app.ister.server.repository;

import app.ister.server.entitiy.NodeEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface NodeRepository extends CrudRepository<NodeEntity, String> {
    Optional<NodeEntity> findByName(String name);
}
