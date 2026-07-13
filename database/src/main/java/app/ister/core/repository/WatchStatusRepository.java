package app.ister.core.repository;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
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

    /** Listening rows: one per user per chapter, keyed by the chapter itself (no play queue). */
    Optional<WatchStatusEntity> findByUserEntityAndChapterEntity(UserEntity userEntity, ChapterEntity chapterEntity);

    List<WatchStatusEntity> findByUserEntityAndChapterEntityBookEntity(UserEntity userEntity, BookEntity bookEntity);

    /** Reading rows: one per user per book, keyed by the book itself (no play queue). */
    Optional<WatchStatusEntity> findByUserEntityAndBookEntity(UserEntity userEntity, BookEntity bookEntity);

    Optional<WatchStatusEntity> findByUserEntityAndPlayQueueItemIdAndPodcastEpisodeEntity(UserEntity userEntity, UUID playQueueItemId, app.ister.core.entity.PodcastEpisodeEntity podcastEpisodeEntity);

    List<WatchStatusEntity> findByUserEntityExternalIdAndPodcastEpisodeEntityIn(String userEntityExternalId, java.util.Collection<app.ister.core.entity.PodcastEpisodeEntity> podcastEpisodeEntities, Sort sort);

    List<WatchStatusEntity> findByUserEntityExternalIdAndChapterEntityIn(String userEntityExternalId, java.util.Collection<ChapterEntity> chapterEntities, Sort sort);

    List<WatchStatusEntity> findByUserEntityExternalIdAndBookEntityIn(String userEntityExternalId, java.util.Collection<BookEntity> bookEntities, Sort sort);

    // Batch variants (used by GraphQL @BatchMapping to avoid N+1)
    List<WatchStatusEntity> findByUserEntityExternalIdAndEpisodeEntityIn(String userEntityExternalId, java.util.Collection<EpisodeEntity> episodeEntities, Sort sort);

    List<WatchStatusEntity> findByUserEntityExternalIdAndMovieEntityIn(String userEntityExternalId, java.util.Collection<MovieEntity> movieEntities, Sort sort);

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

    /**
     * Like {@link #findRecentEpisodesAndShowIdsByUserId} but also returns the date_updated
     * and watched columns so callers can determine how recently the episode was watched and
     * whether it was finished.
     *
     * @return list of Object[] rows: [episode_entity_id (String), show_entity_id (String), date_updated (Timestamp), watched (Boolean)]
     */
    @Query(
            value = """
                    WITH added_row_number AS (
                      SELECT
                        wse.episode_entity_id,
                        ee.show_entity_id,
                        wse.date_updated AS wse_date_updated,
                        wse.watched AS wse_watched,
                        ROW_NUMBER() OVER(PARTITION BY ee.show_entity_id ORDER BY wse.date_updated DESC) AS row_number
                      FROM watch_status_entity wse
                      LEFT JOIN episode_entity ee ON wse.episode_entity_id = ee.id
                      WHERE wse.user_entity_id = :userId AND wse.episode_entity_id IS NOT NULL
                        AND wse.date_updated >= CURRENT_DATE - INTERVAL '150 days'
                      ORDER BY wse.date_updated DESC
                    )
                    SELECT
                      episode_entity_id,
                      show_entity_id,
                      wse_date_updated,
                      wse_watched
                    FROM added_row_number
                    WHERE row_number = 1;
                    """,
            nativeQuery = true
    )
    List<Object[]> findRecentEpisodesWithDateByUserId(@Param("userId") UUID userId);

    /**
     * Movies the user recently started but did not finish (latest watch status per movie has
     * {@code watched = false}). Used by the pre-transcode scheduler: fully watched movies no
     * longer need their HLS cache kept warm.
     */
    @Query(value = """
            SELECT movie_entity_id FROM (
              SELECT DISTINCT ON (wse.movie_entity_id) wse.movie_entity_id, wse.watched
              FROM watch_status_entity wse
              WHERE wse.user_entity_id = :userId
                AND wse.movie_entity_id IS NOT NULL
                AND wse.date_updated >= CURRENT_DATE - INTERVAL '150 days'
              ORDER BY wse.movie_entity_id, wse.date_updated DESC
            ) latest
            WHERE latest.watched = FALSE
            """, nativeQuery = true)
    List<String> findRecentUnwatchedMovieIdsByUserId(@Param("userId") UUID userId);

    @Query(value = """
            SELECT DISTINCT ON (wse.movie_entity_id) wse.movie_entity_id
            FROM watch_status_entity wse
            WHERE wse.user_entity_id = :userId
              AND wse.movie_entity_id IS NOT NULL
              AND wse.date_updated >= CURRENT_DATE - INTERVAL '150 days'
            ORDER BY wse.movie_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<String> findRecentMovieIdsByUserId(@Param("userId") UUID userId);

    /**
     * The most recently listened chapter per book (analog of
     * {@link #findRecentEpisodesAndShowIdsByUserId}): rows of [chapter_entity_id, book_entity_id].
     */
    @Query(
            value = """
                    WITH added_row_number AS (
                      SELECT
                        wse.chapter_entity_id,
                        ce.book_entity_id,
                        ROW_NUMBER() OVER(PARTITION BY ce.book_entity_id ORDER BY wse.date_updated DESC) AS row_number
                      FROM watch_status_entity wse
                      LEFT JOIN chapter_entity ce ON wse.chapter_entity_id = ce.id
                      WHERE wse.user_entity_id = :userId AND wse.chapter_entity_id IS NOT NULL
                        AND wse.date_updated >= CURRENT_DATE - INTERVAL '150 days'
                      ORDER BY wse.date_updated DESC
                    )
                    SELECT
                      added_row_number.chapter_entity_id, added_row_number.book_entity_id
                    FROM added_row_number
                    WHERE row_number = 1;
                    """,
            nativeQuery = true
    )
    List<String[]> findRecentChaptersAndBookIdsByUserId(@Param("userId") UUID userId);

    /** True when someone is mid-episode: started (progress > 0) but not finished. */
    boolean existsByPodcastEpisodeEntityIdAndWatchedFalseAndProgressInMillisecondsGreaterThan(UUID podcastEpisodeId, long progressInMilliseconds);

    /** Podcast episodes the user recently listened to (latest watch status per episode). */
    @Query(value = """
            SELECT DISTINCT ON (wse.podcast_episode_entity_id) wse.podcast_episode_entity_id
            FROM watch_status_entity wse
            WHERE wse.user_entity_id = :userId
              AND wse.podcast_episode_entity_id IS NOT NULL
              AND wse.date_updated >= CURRENT_DATE - INTERVAL '150 days'
            ORDER BY wse.podcast_episode_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<String> findRecentPodcastEpisodeIdsByUserId(@Param("userId") UUID userId);

    /** Books the user recently read in (reading rows: book_entity_id set, no chapter). */
    @Query(value = """
            SELECT DISTINCT ON (wse.book_entity_id) wse.book_entity_id
            FROM watch_status_entity wse
            WHERE wse.user_entity_id = :userId
              AND wse.book_entity_id IS NOT NULL
              AND wse.date_updated >= CURRENT_DATE - INTERVAL '150 days'
            ORDER BY wse.book_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<String> findRecentBookIdsByUserId(@Param("userId") UUID userId);
}
