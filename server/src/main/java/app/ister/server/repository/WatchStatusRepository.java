package app.ister.server.repository;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.MovieEntity;
import app.ister.server.entitiy.UserEntity;
import app.ister.server.entitiy.WatchStatusEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchStatusRepository extends CrudRepository<WatchStatusEntity, UUID> {
    Optional<WatchStatusEntity> findByUserEntityAndPlayQueueItemIdAndEpisodeEntity(UserEntity userEntity, UUID playQueueItemId, EpisodeEntity episodeEntity);

    List<WatchStatusEntity> findByUserEntityExternalIdAndEpisodeEntity(String userEntityExternalId, EpisodeEntity episodeEntity, Sort sort);

    List<WatchStatusEntity> findByUserEntityExternalIdAndMovieEntity(String userEntityExternalId, MovieEntity movieEntity, Sort sort);

    @Query(
            value = "WITH added_row_number AS (\n" +
                    "  SELECT\n" +
                    "    *,\n" +
                    "    ROW_NUMBER() OVER(PARTITION BY ee.show_entity_id ORDER BY wse.date_updated DESC) AS row_number\n" +
                    "  FROM watch_status_entity wse\n" +
                    "  LEFT JOIN episode_entity ee ON wse.episode_entity_id  = ee.id\n" +
                    "  WHERE user_entity_id = :userId\n" +
                    "  order by wse.date_updated desc \n" +
                    ")\n" +
                    "SELECT\n" +
                    "  added_row_number.episode_entity_id, added_row_number.show_entity_id\n" +
                    "FROM added_row_number\n" +
                    "WHERE row_number = 1;",
            nativeQuery = true
    )
    List<String[]> findRecentEpisodesAndShowIdsByUserId(@Param("userId") UUID userId);
}
