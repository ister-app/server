package app.ister.server.repository;

import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.enums.DiskType;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiskRepository extends CrudRepository<DiskEntity, UUID> {
    List<DiskEntity> findByDiskType(DiskType diskType);
}
