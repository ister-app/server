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

import java.time.Instant;
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

    /** True when someone is mid-episode: started (progress > 0) but not finished. */
    boolean existsByPodcastEpisodeEntityIdAndWatchedFalseAndProgressInMillisecondsGreaterThan(UUID podcastEpisodeId, long progressInMilliseconds);

    /**
     * The latest watch status of one container, as needed to rebuild a continue-watching entry:
     * what the user last played, when, and how far they got.
     *
     * @see app.ister.core.service.ContinueWatchingService
     */
    interface RecentEntry {

        /** The episode / movie / chapter / book / podcast episode the row is about. */
        UUID getItemId();

        /** Its container: the show of an episode, the book of a chapter; the item itself otherwise. */
        UUID getGroupId();

        Instant getLastWatched();

        boolean getWatched();

        long getProgressInMilliseconds();

        Double getReadingProgress();
    }

    /** The episode the user last played of every show they touched since the cutoff, one row per show. */
    @Query(value = """
            SELECT DISTINCT ON (ee.show_entity_id)
              wse.episode_entity_id AS "itemId",
              ee.show_entity_id AS "groupId",
              wse.date_updated AS "lastWatched",
              wse.watched AS "watched",
              wse.progress_in_milliseconds AS "progressInMilliseconds",
              wse.reading_progress AS "readingProgress"
            FROM watch_status_entity wse
            JOIN episode_entity ee ON wse.episode_entity_id = ee.id
            WHERE wse.user_entity_id = :userId AND wse.date_updated >= :cutoff
            ORDER BY ee.show_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<RecentEntry> findRecentEpisodeEntries(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    /** The chapter the user last played of every audiobook they touched since the cutoff. */
    @Query(value = """
            SELECT DISTINCT ON (ce.book_entity_id)
              wse.chapter_entity_id AS "itemId",
              ce.book_entity_id AS "groupId",
              wse.date_updated AS "lastWatched",
              wse.watched AS "watched",
              wse.progress_in_milliseconds AS "progressInMilliseconds",
              wse.reading_progress AS "readingProgress"
            FROM watch_status_entity wse
            JOIN chapter_entity ce ON wse.chapter_entity_id = ce.id
            WHERE wse.user_entity_id = :userId AND wse.date_updated >= :cutoff
            ORDER BY ce.book_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<RecentEntry> findRecentChapterEntries(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    @Query(value = """
            SELECT DISTINCT ON (wse.movie_entity_id)
              wse.movie_entity_id AS "itemId",
              wse.movie_entity_id AS "groupId",
              wse.date_updated AS "lastWatched",
              wse.watched AS "watched",
              wse.progress_in_milliseconds AS "progressInMilliseconds",
              wse.reading_progress AS "readingProgress"
            FROM watch_status_entity wse
            WHERE wse.user_entity_id = :userId AND wse.movie_entity_id IS NOT NULL
              AND wse.date_updated >= :cutoff
            ORDER BY wse.movie_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<RecentEntry> findRecentMovieEntries(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    /** Books the user read in (reading rows: book_entity_id set, no chapter). */
    @Query(value = """
            SELECT DISTINCT ON (wse.book_entity_id)
              wse.book_entity_id AS "itemId",
              wse.book_entity_id AS "groupId",
              wse.date_updated AS "lastWatched",
              wse.watched AS "watched",
              wse.progress_in_milliseconds AS "progressInMilliseconds",
              wse.reading_progress AS "readingProgress"
            FROM watch_status_entity wse
            WHERE wse.user_entity_id = :userId AND wse.book_entity_id IS NOT NULL
              AND wse.date_updated >= :cutoff
            ORDER BY wse.book_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<RecentEntry> findRecentBookEntries(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    @Query(value = """
            SELECT DISTINCT ON (wse.podcast_episode_entity_id)
              wse.podcast_episode_entity_id AS "itemId",
              wse.podcast_episode_entity_id AS "groupId",
              wse.date_updated AS "lastWatched",
              wse.watched AS "watched",
              wse.progress_in_milliseconds AS "progressInMilliseconds",
              wse.reading_progress AS "readingProgress"
            FROM watch_status_entity wse
            WHERE wse.user_entity_id = :userId AND wse.podcast_episode_entity_id IS NOT NULL
              AND wse.date_updated >= :cutoff
            ORDER BY wse.podcast_episode_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<RecentEntry> findRecentPodcastEpisodeEntries(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);
}
