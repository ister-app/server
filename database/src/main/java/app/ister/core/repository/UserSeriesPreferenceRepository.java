package app.ister.core.repository;

import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserSeriesPreferenceEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSeriesPreferenceRepository extends CrudRepository<UserSeriesPreferenceEntity, UUID> {

    // Write path: the caller's existing preference for a single series.
    Optional<UserSeriesPreferenceEntity> findByUserEntityAndSeriesEntity(UserEntity userEntity, SeriesEntity seriesEntity);

    // Batch variant (used by the GraphQL @BatchMapping to avoid N+1).
    List<UserSeriesPreferenceEntity> findByUserEntityExternalIdAndSeriesEntityIn(
            String userEntityExternalId, Collection<SeriesEntity> seriesEntities);
}
