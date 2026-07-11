package app.ister.core.repository;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RatingRepository extends CrudRepository<RatingEntity, UUID> {

    // Write path: look up the caller's existing rating for a single item.
    Optional<RatingEntity> findByUserEntityAndMovieEntity(UserEntity userEntity, MovieEntity movieEntity);

    Optional<RatingEntity> findByUserEntityAndShowEntity(UserEntity userEntity, ShowEntity showEntity);

    Optional<RatingEntity> findByUserEntityAndEpisodeEntity(UserEntity userEntity, EpisodeEntity episodeEntity);

    Optional<RatingEntity> findByUserEntityAndAlbumEntity(UserEntity userEntity, AlbumEntity albumEntity);

    Optional<RatingEntity> findByUserEntityAndTrackEntity(UserEntity userEntity, TrackEntity trackEntity);

    // Batch variants (used by GraphQL @BatchMapping to avoid N+1).
    List<RatingEntity> findByUserEntityExternalIdAndMovieEntityIn(String userEntityExternalId, Collection<MovieEntity> movieEntities);

    List<RatingEntity> findByUserEntityExternalIdAndShowEntityIn(String userEntityExternalId, Collection<ShowEntity> showEntities);

    List<RatingEntity> findByUserEntityExternalIdAndEpisodeEntityIn(String userEntityExternalId, Collection<EpisodeEntity> episodeEntities);

    List<RatingEntity> findByUserEntityExternalIdAndAlbumEntityIn(String userEntityExternalId, Collection<AlbumEntity> albumEntities);

    List<RatingEntity> findByUserEntityExternalIdAndTrackEntityIn(String userEntityExternalId, Collection<TrackEntity> trackEntities);
}
