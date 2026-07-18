package app.ister.core.repository;

import app.ister.core.entity.UserSharingSettingsEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSharingSettingsRepository extends CrudRepository<UserSharingSettingsEntity, UUID> {

    Optional<UserSharingSettingsEntity> findByUserEntityId(UUID userEntityId);

    Optional<UserSharingSettingsEntity> findByUserEntityExternalId(String externalId);
}
