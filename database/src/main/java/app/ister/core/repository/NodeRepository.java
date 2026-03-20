package app.ister.core.repository;

import app.ister.core.entity.NodeEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface NodeRepository extends CrudRepository<NodeEntity, String> {
    Optional<NodeEntity> findByName(String name);
}
