package app.ister.core.repository;

import app.ister.core.entity.ContinueWatchingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ContinueWatchingRepository extends JpaRepository<ContinueWatchingEntity, UUID> {

    /**
     * The user's continue-watching list: entries within the history window that still have
     * something to resume, newest first. Served by the (user_entity_id, last_watched DESC) index.
     */
    @Query("""
            SELECT c FROM ContinueWatchingEntity c
            WHERE c.userEntity.id = :userId AND c.lastWatched >= :cutoff
              AND (c.episodeEntity IS NOT NULL OR c.movieEntity IS NOT NULL OR c.chapterEntity IS NOT NULL
                   OR c.bookEntity IS NOT NULL OR c.podcastEpisodeEntity IS NOT NULL)
            ORDER BY c.lastWatched DESC""")
    List<ContinueWatchingEntity> findEntries(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    /**
     * Entries of one container that have run out of things to resume — a show whose episodes the
     * user has all seen. Re-evaluated when the scanner adds an episode or chapter to it.
     */
    @Query("""
            SELECT c FROM ContinueWatchingEntity c
            WHERE c.entryType = app.ister.core.enums.MediaType.EPISODE AND c.groupId = :showId
              AND c.episodeEntity IS NULL""")
    List<ContinueWatchingEntity> findExhaustedShowEntries(@Param("showId") UUID showId);

    @Query("""
            SELECT c FROM ContinueWatchingEntity c
            WHERE c.entryType = app.ister.core.enums.MediaType.BOOK AND c.groupId = :bookId
              AND c.chapterEntity IS NULL""")
    List<ContinueWatchingEntity> findExhaustedBookEntries(@Param("bookId") UUID bookId);

    void deleteByUserEntityId(UUID userId);

    /**
     * Writes one entry. A native upsert rather than a find-then-save: two playback heartbeats of the
     * same user can otherwise race into a unique-constraint violation on
     * (user_entity_id, entry_type, group_id), and a heartbeat must never fail on bookkeeping.
     *
     * <p>{@code last_watched} only ever moves forward, so a heartbeat that arrives out of order
     * cannot pull an entry back down the list.
     */
    // Not clearAutomatically: the callers (the playback heartbeat) keep working with managed
    // entities after this runs, and detaching them mid-transaction breaks their lazy loading.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO continue_watching (id, date_created, date_updated, user_entity_id, entry_type, group_id,
                                           episode_entity_id, movie_entity_id, chapter_entity_id, book_entity_id,
                                           podcast_episode_entity_id, last_watched)
            VALUES (gen_random_uuid(), now(), now(), :userId, :entryType, :groupId,
                    :episodeId, :movieId, :chapterId, :bookId, :podcastEpisodeId, :lastWatched)
            ON CONFLICT (user_entity_id, entry_type, group_id) DO UPDATE SET
                date_updated = now(),
                episode_entity_id = EXCLUDED.episode_entity_id,
                movie_entity_id = EXCLUDED.movie_entity_id,
                chapter_entity_id = EXCLUDED.chapter_entity_id,
                book_entity_id = EXCLUDED.book_entity_id,
                podcast_episode_entity_id = EXCLUDED.podcast_episode_entity_id,
                last_watched = GREATEST(continue_watching.last_watched, EXCLUDED.last_watched)""",
            nativeQuery = true)
    void upsert(@Param("userId") UUID userId,
                @Param("entryType") String entryType,
                @Param("groupId") UUID groupId,
                @Param("episodeId") UUID episodeId,
                @Param("movieId") UUID movieId,
                @Param("chapterId") UUID chapterId,
                @Param("bookId") UUID bookId,
                @Param("podcastEpisodeId") UUID podcastEpisodeId,
                @Param("lastWatched") Instant lastWatched);

    /**
     * The audio slot of a book's single {@code BOOK} entry. A book can be both read (epub) and
     * listened to (chapters); the two are independent progress slots on one row, so unlike the
     * generic {@link #upsert} this touches <em>only</em> {@code chapter_entity_id} and leaves the
     * epub slot ({@code book_entity_id}) untouched — otherwise a reading heartbeat would wipe the
     * listening position and vice versa.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO continue_watching (id, date_created, date_updated, user_entity_id, entry_type, group_id,
                                           episode_entity_id, movie_entity_id, chapter_entity_id, book_entity_id,
                                           podcast_episode_entity_id, last_watched)
            VALUES (gen_random_uuid(), now(), now(), :userId, 'BOOK', :bookId,
                    NULL, NULL, :chapterId, NULL, NULL, :lastWatched)
            ON CONFLICT (user_entity_id, entry_type, group_id) DO UPDATE SET
                date_updated = now(),
                chapter_entity_id = EXCLUDED.chapter_entity_id,
                last_watched = GREATEST(continue_watching.last_watched, EXCLUDED.last_watched)""",
            nativeQuery = true)
    void upsertBookAudio(@Param("userId") UUID userId,
                         @Param("bookId") UUID bookId,
                         @Param("chapterId") UUID chapterId,
                         @Param("lastWatched") Instant lastWatched);

    /**
     * The epub (reading) slot of a book's single {@code BOOK} entry. Mirror of
     * {@link #upsertBookAudio}: touches only {@code book_entity_id}, leaving the audio slot intact.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO continue_watching (id, date_created, date_updated, user_entity_id, entry_type, group_id,
                                           episode_entity_id, movie_entity_id, chapter_entity_id, book_entity_id,
                                           podcast_episode_entity_id, last_watched)
            VALUES (gen_random_uuid(), now(), now(), :userId, 'BOOK', :bookId,
                    NULL, NULL, NULL, :bookTargetId, NULL, :lastWatched)
            ON CONFLICT (user_entity_id, entry_type, group_id) DO UPDATE SET
                date_updated = now(),
                book_entity_id = EXCLUDED.book_entity_id,
                last_watched = GREATEST(continue_watching.last_watched, EXCLUDED.last_watched)""",
            nativeQuery = true)
    void upsertBookEpub(@Param("userId") UUID userId,
                        @Param("bookId") UUID bookId,
                        @Param("bookTargetId") UUID bookTargetId,
                        @Param("lastWatched") Instant lastWatched);
}
