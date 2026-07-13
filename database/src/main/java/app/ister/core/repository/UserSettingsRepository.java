package app.ister.core.repository;

import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserSettingsEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSettingsRepository extends CrudRepository<UserSettingsEntity, UUID> {

    Optional<UserSettingsEntity> findByUserEntity(UserEntity userEntity);

    Optional<UserSettingsEntity> findByUserEntityId(UUID userEntityId);
}
