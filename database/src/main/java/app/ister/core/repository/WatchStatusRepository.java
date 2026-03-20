package app.ister.core.repository;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
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

    /**
     * Retrieves the most recent episode and show IDs for a specific user based on their watch status.
     * The method filters the results to include only those watch status records that have been updated
     * in the last 150 days.
     *
     * @param userId the unique identifier of the user whose watch status is being queried
     * @return a list of string arrays, where each array contains the episode entity ID and the show entity ID
     *         for the most recent episodes watched by the specified user. The list may be empty if no
     *         records are found or if the user has no watch status updates in the last 150 days.
     */
    @Query(
            value = """
                    WITH added_row_number AS (
                      SELECT
                        *,
                        ROW_NUMBER() OVER(PARTITION BY ee.show_entity_id ORDER BY wse.date_updated DESC) AS row_number
                      FROM watch_status_entity wse
                      LEFT JOIN episode_entity ee ON wse.episode_entity_id  = ee.id
                      WHERE user_entity_id = :userId AND episode_entity_id IS NOT NULL
                        AND wse.date_updated >= CURRENT_DATE - INTERVAL '150 days'
                      ORDER BY wse.date_updated DESC
                    )
                    SELECT
                      added_row_number.episode_entity_id, added_row_number.show_entity_id
                    FROM added_row_number
                    WHERE row_number = 1;
                    """,
            nativeQuery = true
    )
    List<String[]> findRecentEpisodesAndShowIdsByUserId(@Param("userId") UUID userId);
}
