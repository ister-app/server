package app.ister.core.repository;

import app.ister.core.entity.DeviceEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends CrudRepository<DeviceEntity, UUID> {

    Optional<DeviceEntity> findByUserEntityIdAndDeviceId(UUID userEntityId, UUID deviceId);

    List<DeviceEntity> findByUserEntityIdOrderByLastSeenAtDesc(UUID userEntityId);
}
