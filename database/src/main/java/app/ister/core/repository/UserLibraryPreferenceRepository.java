package app.ister.core.repository;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserLibraryPreferenceEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserLibraryPreferenceRepository extends CrudRepository<UserLibraryPreferenceEntity, UUID> {

    // Write path: the caller's existing preference for a single library.
    Optional<UserLibraryPreferenceEntity> findByUserEntityAndLibraryEntity(UserEntity userEntity, LibraryEntity libraryEntity);

    // Batch variant (used by the GraphQL @BatchMapping to avoid N+1).
    List<UserLibraryPreferenceEntity> findByUserEntityExternalIdAndLibraryEntityIn(
            String userEntityExternalId, Collection<LibraryEntity> libraryEntities);
}
