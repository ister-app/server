package app.ister.core.repository;

import app.ister.core.entity.UserLibraryAccessEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserLibraryAccessRepository extends CrudRepository<UserLibraryAccessEntity, UUID> {

    List<UserLibraryAccessEntity> findByUserEntityExternalId(String externalId);

    List<UserLibraryAccessEntity> findByUserEntityId(UUID userEntityId);

    Optional<UserLibraryAccessEntity> findByUserEntityIdAndLibraryEntityId(UUID userEntityId, UUID libraryEntityId);
}
