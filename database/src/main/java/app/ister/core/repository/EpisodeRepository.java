package app.ister.core.repository;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EpisodeRepository extends JpaRepository<EpisodeEntity, UUID> {

    Optional<EpisodeEntity> findByShowEntityAndSeasonEntityAndNumber(ShowEntity showEntity, SeasonEntity seasonEntity, int number);

    Page<EpisodeEntity> findAll(Pageable pageable);

    List<EpisodeEntity> findBySeasonEntityIdOrderByNumberAsc(UUID season);

    List<IdOnly> findIdsOnlyByShowEntityId(UUID season, Sort sort);

    List<EpisodeEntity> findByShowEntityId(UUID season, Sort sort);

    // Batch variant (used by GraphQL @BatchMapping to avoid N+1)
    List<EpisodeEntity> findByShowEntityIdIn(java.util.Collection<UUID> showEntityIds, Sort sort);

    /**
     * Returns a page of episode IDs of a show in natural play order (season number, episode number).
     */
    @Query(value = """
            SELECT e.id FROM episode_entity e
            JOIN season_entity s ON e.season_entity_id = s.id
            WHERE e.show_entity_id = :showId
            ORDER BY s.number, e.number, e.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findEpisodeIdsForShowOrdered(@Param("showId") UUID showId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * Returns a page of episode IDs of a show in a deterministic shuffled order derived from the seed.
     */
    @Query(value = """
            SELECT e.id FROM episode_entity e
            WHERE e.show_entity_id = :showId AND e.id <> :excludeId
            ORDER BY md5(e.id::text || :seed), e.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findEpisodeIdsForShowShuffled(@Param("showId") UUID showId, @Param("seed") String seed, @Param("excludeId") UUID excludeId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * The episode a user should continue a show with: the first episode in play order (season
     * number, episode number) after the given position that the user has not finished. Returns an
     * empty list when the user is up to date with the show.
     *
     * <p>NOT EXISTS rather than a join on watch_status_entity: an episode can have several watch
     * rows for one user (one per play queue item), and a join would multiply them.
     *
     * @param afterSeason  season number of the episode to search from (exclusive)
     * @param afterEpisode episode number within that season (exclusive)
     */
    @Query(value = """
            SELECT e.id FROM episode_entity e
            JOIN season_entity s ON e.season_entity_id = s.id
            WHERE e.show_entity_id = :showId
              AND (s.number, e.number) > (:afterSeason, :afterEpisode)
              AND NOT EXISTS (SELECT 1 FROM watch_status_entity w
                              WHERE w.episode_entity_id = e.id AND w.user_entity_id = :userId AND w.watched)
            ORDER BY s.number, e.number, e.id
            LIMIT 1""", nativeQuery = true)
    List<UUID> findNextUnwatchedEpisodeId(@Param("showId") UUID showId,
                                          @Param("userId") UUID userId,
                                          @Param("afterSeason") int afterSeason,
                                          @Param("afterEpisode") int afterEpisode);

    /**
     * The episode that simply follows the given position in play order, watched or not. Used by
     * pre-transcoding to keep the episode after the one the user is about to play warm as well.
     */
    @Query(value = """
            SELECT e.id FROM episode_entity e
            JOIN season_entity s ON e.season_entity_id = s.id
            WHERE e.show_entity_id = :showId
              AND (s.number, e.number) > (:afterSeason, :afterEpisode)
            ORDER BY s.number, e.number, e.id
            LIMIT 1""", nativeQuery = true)
    List<UUID> findNextEpisodeId(@Param("showId") UUID showId,
                                 @Param("afterSeason") int afterSeason,
                                 @Param("afterEpisode") int afterEpisode);

    /**
     * Returns the IDs (UUID) of episodes that have no {@link MetadataEntity} linked to them.
     */
    @Query("SELECT s.id FROM EpisodeEntity s LEFT JOIN s.metadataEntities m " +
            "WHERE m IS NULL")
    List<UUID> findIdsOfEpisodesWithoutMetadata();

    /**
     * Returns the IDs (UUID) of episodes that have no {@link MetadataEntity} linked to them
     * and have a media file on the given node.
     */
    @Query("SELECT DISTINCT e.id FROM EpisodeEntity e LEFT JOIN e.metadataEntities m " +
            "JOIN e.mediaFileEntities mf JOIN mf.directoryEntity d " +
            "WHERE m IS NULL AND d.nodeEntity.name = :nodeName")
    List<UUID> findIdsOfEpisodesWithoutMetadataForNode(@Param("nodeName") String nodeName);

    interface IdOnly {

        UUID getId();
    }
}
