package app.ister.core.repository;

import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserPodcastPreferenceEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserPodcastPreferenceRepository extends CrudRepository<UserPodcastPreferenceEntity, UUID> {

    // Write path: the caller's existing preference for a single podcast.
    Optional<UserPodcastPreferenceEntity> findByUserEntityAndPodcastEntity(UserEntity userEntity, PodcastEntity podcastEntity);

    // Batch variant (used by the GraphQL @BatchMapping to avoid N+1).
    List<UserPodcastPreferenceEntity> findByUserEntityExternalIdAndPodcastEntityIn(
            String userEntityExternalId, Collection<PodcastEntity> podcastEntities);
}
