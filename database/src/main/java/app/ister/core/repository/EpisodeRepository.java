package app.ister.core.repository;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EpisodeRepository extends JpaRepository<EpisodeEntity, UUID> {

    Optional<EpisodeEntity> findByShowEntityAndSeasonEntityAndNumber(ShowEntity showEntity, SeasonEntity seasonEntity, int number);

    Page<EpisodeEntity> findAll(Pageable pageable);

    /**
     * A page of every episode of the given libraries, sorted for the library-wide browse grid.
     * The episode's title and air date live in its metadata rows (possibly several per episode),
     * so the metadata-based sorts aggregate with MIN over a left join rather than sorting a plain
     * column; the join with GROUP BY keeps each episode a single row. Episodes without a title or
     * air date sort last in both directions. The id tie-break keeps paging stable (see
     * {@code Paging}).
     */
    default Page<EpisodeEntity> findInLibraries(Collection<UUID> libraryIds, SortingEnum sorting,
                                                SortingOrder sortingOrder, Pageable pageable) {
        boolean ascending = sortingOrder == SortingOrder.ASCENDING;
        return switch (sorting) {
            case NAME -> ascending ? findInLibrariesOrderByTitleAsc(libraryIds, pageable)
                    : findInLibrariesOrderByTitleDesc(libraryIds, pageable);
            case RELEASE_YEAR -> ascending ? findInLibrariesOrderByReleasedAsc(libraryIds, pageable)
                    : findInLibrariesOrderByReleasedDesc(libraryIds, pageable);
            case DATE_CREATED -> {
                Sort sort = ascending ? Sort.by("dateCreated").ascending() : Sort.by("dateCreated").descending();
                yield findByShowEntityLibraryEntityIdIn(libraryIds,
                        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by("id"))));
            }
        };
    }

    String EPISODES_IN_LIBRARIES =
            " FROM EpisodeEntity e LEFT JOIN e.metadataEntities m WHERE e.showEntity.libraryEntity.id IN :libraryIds ";
    String COUNT_EPISODES_IN_LIBRARIES =
            "SELECT COUNT(e) FROM EpisodeEntity e WHERE e.showEntity.libraryEntity.id IN :libraryIds";

    @Query(value = "SELECT e" + EPISODES_IN_LIBRARIES + "GROUP BY e ORDER BY MIN(m.title) ASC NULLS LAST, e.id",
            countQuery = COUNT_EPISODES_IN_LIBRARIES)
    Page<EpisodeEntity> findInLibrariesOrderByTitleAsc(@Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT e" + EPISODES_IN_LIBRARIES + "GROUP BY e ORDER BY MIN(m.title) DESC NULLS LAST, e.id",
            countQuery = COUNT_EPISODES_IN_LIBRARIES)
    Page<EpisodeEntity> findInLibrariesOrderByTitleDesc(@Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT e" + EPISODES_IN_LIBRARIES + "GROUP BY e ORDER BY MIN(m.released) ASC NULLS LAST, e.id",
            countQuery = COUNT_EPISODES_IN_LIBRARIES)
    Page<EpisodeEntity> findInLibrariesOrderByReleasedAsc(@Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT e" + EPISODES_IN_LIBRARIES + "GROUP BY e ORDER BY MIN(m.released) DESC NULLS LAST, e.id",
            countQuery = COUNT_EPISODES_IN_LIBRARIES)
    Page<EpisodeEntity> findInLibrariesOrderByReleasedDesc(@Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    Page<EpisodeEntity> findByShowEntityLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);

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
     * Returns the IDs (UUID) of episodes that have no {@link MetadataEntity} linked to them,
     * optionally scoped to one library. Unlike movies/shows there is no tmdbId marker on episodes;
     * re-enriching episodes that already have metadata needs the FORCE refresh flow.
     */
    @Query("SELECT DISTINCT e.id FROM EpisodeEntity e LEFT JOIN e.metadataEntities m " +
            "WHERE m IS NULL " +
            "AND (:libraryId IS NULL OR e.showEntity.libraryEntity.id = :libraryId)")
    List<UUID> findIdsOfEpisodesWithoutMetadata(@Param("libraryId") UUID libraryId);

    interface IdOnly {

        UUID getId();
    }
}
