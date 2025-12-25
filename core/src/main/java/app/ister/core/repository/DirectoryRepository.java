package app.ister.core.repository;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.NodeEntity;
import app.ister.core.enums.DirectoryType;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DirectoryRepository extends CrudRepository<DirectoryEntity, UUID> {
    Optional<DirectoryEntity> findByName(String name);

    List<DirectoryEntity> findByDirectoryTypeAndNodeEntity(DirectoryType directoryType, NodeEntity nodeEntity);
}
