package app.ister.server.repository;

import app.ister.server.entitiy.NodeEntity;
import org.springframework.data.repository.CrudRepository;

public interface NodeRepository extends CrudRepository<NodeEntity, String> {
}
