package app.ister.server.repository;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.enums.DirectoryType;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DirectoryRepository extends CrudRepository<DirectoryEntity, UUID> {
    Optional<DirectoryEntity> findByName(String name);
    List<DirectoryEntity> findByDirectoryTypeAndNodeEntity(DirectoryType directoryType, NodeEntity nodeEntity);
}
