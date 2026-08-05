package app.ister.core.repository;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchStatusRepository extends JpaRepository<WatchStatusEntity, UUID> {
    Optional<WatchStatusEntity> findByUserEntityAndPlayQueueItemIdAndEpisodeEntity(UserEntity userEntity, UUID playQueueItemId, EpisodeEntity episodeEntity);

    List<WatchStatusEntity> findByUserEntityExternalIdAndEpisodeEntity(String userEntityExternalId, EpisodeEntity episodeEntity, Sort sort);

    List<WatchStatusEntity> findByUserEntityExternalIdAndMovieEntity(String userEntityExternalId, MovieEntity movieEntity, Sort sort);

    /** Listening rows: one per user per chapter, keyed by the chapter itself (no play queue). */
    Optional<WatchStatusEntity> findByUserEntityAndChapterEntity(UserEntity userEntity, ChapterEntity chapterEntity);

    List<WatchStatusEntity> findByUserEntityAndChapterEntityBookEntity(UserEntity userEntity, BookEntity bookEntity);

    /** Whether the user has ever listened to (has a chapter watch-status for) any chapter of a book. */
    boolean existsByUserEntityIdAndChapterEntityBookEntityId(UUID userEntityId, UUID bookEntityId);

    /** Reading rows: one per user per book, keyed by the book itself (no play queue). */
    Optional<WatchStatusEntity> findByUserEntityAndBookEntity(UserEntity userEntity, BookEntity bookEntity);

    Optional<WatchStatusEntity> findByUserEntityAndPlayQueueItemIdAndPodcastEpisodeEntity(UserEntity userEntity, UUID playQueueItemId, app.ister.core.entity.PodcastEpisodeEntity podcastEpisodeEntity);

    Optional<WatchStatusEntity> findByUserEntityAndPlayQueueItemIdAndTrackEntity(UserEntity userEntity, UUID playQueueItemId, app.ister.core.entity.TrackEntity trackEntity);

    List<WatchStatusEntity> findByUserEntityExternalIdAndPodcastEpisodeEntityIn(String userEntityExternalId, java.util.Collection<app.ister.core.entity.PodcastEpisodeEntity> podcastEpisodeEntities, Sort sort);

    List<WatchStatusEntity> findByUserEntityExternalIdAndChapterEntityIn(String userEntityExternalId, java.util.Collection<ChapterEntity> chapterEntities, Sort sort);

    List<WatchStatusEntity> findByUserEntityExternalIdAndBookEntityIn(String userEntityExternalId, java.util.Collection<BookEntity> bookEntities, Sort sort);

    // Batch variants (used by GraphQL @BatchMapping to avoid N+1)
    List<WatchStatusEntity> findByUserEntityExternalIdAndEpisodeEntityIn(String userEntityExternalId, java.util.Collection<EpisodeEntity> episodeEntities, Sort sort);

    List<WatchStatusEntity> findByUserEntityExternalIdAndMovieEntityIn(String userEntityExternalId, java.util.Collection<MovieEntity> movieEntities, Sort sort);

    /** True when someone is mid-episode: started (progress > 0) but not finished. */
    boolean existsByPodcastEpisodeEntityIdAndWatchedFalseAndProgressInMillisecondsGreaterThan(UUID podcastEpisodeId, long progressInMilliseconds);

    /** One chapter of a book, with its duration and how far the user got in it. */
    interface ChapterProgressRow {

        UUID getBookId();

        UUID getChapterId();

        /** Null when the chapter has no media file, or it was never analysed. */
        Long getDurationInMilliseconds();

        /** Null when the user never played this chapter. */
        Boolean getWatched();

        Long getProgressInMilliseconds();

        Instant getUpdatedAt();
    }

    /**
     * Every chapter of the given books with the calling user's position in it — the raw material for
     * whole-book listening progress. One query for a whole carousel of books; chapters the user
     * never touched come back with null watch columns.
     */
    @Query(value = """
            SELECT c.book_entity_id AS "bookId",
              c.id AS "chapterId",
              mf.duration_in_milliseconds AS "durationInMilliseconds",
              w.watched AS "watched",
              w.progress_in_milliseconds AS "progressInMilliseconds",
              w.date_updated AS "updatedAt"
            FROM chapter_entity c
            LEFT JOIN LATERAL (SELECT m.duration_in_milliseconds
                               FROM media_file_entity m
                               WHERE m.chapter_entity_id = c.id
                               ORDER BY m.id
                               LIMIT 1) mf ON TRUE
            LEFT JOIN watch_status_entity w
              ON w.chapter_entity_id = c.id AND w.user_entity_id = :userId
            WHERE c.book_entity_id IN (:bookIds)
            """, nativeQuery = true)
    List<ChapterProgressRow> findChapterProgress(@Param("userId") UUID userId,
                                                 @Param("bookIds") java.util.Collection<UUID> bookIds);

    /** Per-track play statistics of one user: number of plays and when it was last played. */
    interface TrackPlayStats {

        UUID getTrackId();

        long getPlays();

        Instant getLastPlayedAt();
    }

    /** Play statistics for a batch of tracks (GraphQL {@code Track.playCount}/{@code lastPlayedAt}). */
    @Query(value = """
            SELECT wse.track_entity_id AS "trackId",
              COUNT(*) AS "plays",
              MAX(wse.date_updated) AS "lastPlayedAt"
            FROM watch_status_entity wse
            JOIN user_entity u ON u.id = wse.user_entity_id
            WHERE u.external_id = :externalId AND wse.track_entity_id IN (:trackIds)
            GROUP BY wse.track_entity_id
            """, nativeQuery = true)
    List<TrackPlayStats> findTrackPlayStats(@Param("externalId") String externalId, @Param("trackIds") java.util.Collection<UUID> trackIds);

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

    /**
     * Books the user read in (reading rows: book_entity_id set, no chapter). Comic volumes are
     * excluded — they group per series in {@link #findRecentComicEntries}, and one volume must
     * never produce both a BOOK and a COMIC entry.
     */
    @Query(value = """
            SELECT DISTINCT ON (wse.book_entity_id)
              wse.book_entity_id AS "itemId",
              wse.book_entity_id AS "groupId",
              wse.date_updated AS "lastWatched",
              wse.watched AS "watched",
              wse.progress_in_milliseconds AS "progressInMilliseconds",
              wse.reading_progress AS "readingProgress"
            FROM watch_status_entity wse
            JOIN book_entity b ON wse.book_entity_id = b.id
            JOIN library_entity l ON b.library_entity_id = l.id
            WHERE wse.user_entity_id = :userId AND wse.date_updated >= :cutoff
              AND l.library_type <> 'COMIC'
            ORDER BY wse.book_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<RecentEntry> findRecentBookEntries(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    /** The comic volume the user last read of every series they touched, one row per series. */
    @Query(value = """
            SELECT DISTINCT ON (b.series_entity_id)
              wse.book_entity_id AS "itemId",
              b.series_entity_id AS "groupId",
              wse.date_updated AS "lastWatched",
              wse.watched AS "watched",
              wse.progress_in_milliseconds AS "progressInMilliseconds",
              wse.reading_progress AS "readingProgress"
            FROM watch_status_entity wse
            JOIN book_entity b ON wse.book_entity_id = b.id
            JOIN library_entity l ON b.library_entity_id = l.id
            WHERE wse.user_entity_id = :userId AND wse.date_updated >= :cutoff
              AND l.library_type = 'COMIC' AND b.series_entity_id IS NOT NULL
            ORDER BY b.series_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<RecentEntry> findRecentComicEntries(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    /** The episode the user last played of every podcast they touched since the cutoff, one row per podcast. */
    @Query(value = """
            SELECT DISTINCT ON (pe.podcast_entity_id)
              wse.podcast_episode_entity_id AS "itemId",
              pe.podcast_entity_id AS "groupId",
              wse.date_updated AS "lastWatched",
              wse.watched AS "watched",
              wse.progress_in_milliseconds AS "progressInMilliseconds",
              wse.reading_progress AS "readingProgress"
            FROM watch_status_entity wse
            JOIN podcast_episode_entity pe ON wse.podcast_episode_entity_id = pe.id
            WHERE wse.user_entity_id = :userId AND wse.date_updated >= :cutoff
            ORDER BY pe.podcast_entity_id, wse.date_updated DESC
            """, nativeQuery = true)
    List<RecentEntry> findRecentPodcastEpisodeEntries(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);
}
