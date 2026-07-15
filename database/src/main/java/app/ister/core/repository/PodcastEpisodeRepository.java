package app.ister.core.repository;

import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PodcastEpisodeRepository extends JpaRepository<PodcastEpisodeEntity, UUID> {
    Optional<PodcastEpisodeEntity> findByPodcastEntityAndGuid(PodcastEntity podcastEntity, String guid);

    /** Feed order: newest first. Feeds can hold hundreds of episodes, so always paged. */
    Page<PodcastEpisodeEntity> findByPodcastEntityIdOrderByPublishedAtDesc(UUID podcastId, Pageable pageable);

    /**
     * Episodes of a podcast in the sort order carried by the pageable — used by the GraphQL query,
     * which sorts by the caller's stored preference instead of a fixed direction.
     */
    Page<PodcastEpisodeEntity> findByPodcastEntityId(UUID podcastId, Pageable pageable);

    /**
     * Returns a page of episode IDs of a podcast, newest first. Besides being the default play
     * order, this is how the refresh worker picks the N newest episodes to auto-download — that
     * always means newest, regardless of any user's sort preference.
     */
    @Query(value = """
            SELECT e.id FROM podcast_episode_entity e
            WHERE e.podcast_entity_id = :podcastId
            ORDER BY e.published_at DESC NULLS LAST, e.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findEpisodeIdsForPodcastOrdered(@Param("podcastId") UUID podcastId, @Param("limit") int limit, @Param("offset") int offset);

    /** Oldest first: the play order of a podcast queue built for a user who prefers ASCENDING. */
    @Query(value = """
            SELECT e.id FROM podcast_episode_entity e
            WHERE e.podcast_entity_id = :podcastId
            ORDER BY e.published_at ASC NULLS LAST, e.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findEpisodeIdsForPodcastOrderedAsc(@Param("podcastId") UUID podcastId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * The episode a user should continue a podcast with after finishing one: the next episode in
     * chronological play order (published_at) they have not finished. Empty when there is nothing
     * newer left to resume. The podcast twin of {@link EpisodeRepository#findNextUnwatchedEpisodeId}
     * and {@link ChapterRepository#findNextUnfinishedChapterId}; published_at can be null, so it is
     * coalesced to the epoch to keep the cursor comparison total.
     */
    @Query(value = """
            SELECT e.id FROM podcast_episode_entity e
            JOIN podcast_episode_entity cur ON cur.id = :afterEpisodeId
            WHERE e.podcast_entity_id = :podcastId
              AND (COALESCE(e.published_at, TIMESTAMPTZ 'epoch'), e.id)
                > (COALESCE(cur.published_at, TIMESTAMPTZ 'epoch'), cur.id)
              AND NOT EXISTS (SELECT 1 FROM watch_status_entity w
                              WHERE w.podcast_episode_entity_id = e.id AND w.user_entity_id = :userId AND w.watched)
            ORDER BY COALESCE(e.published_at, TIMESTAMPTZ 'epoch'), e.id
            LIMIT 1""", nativeQuery = true)
    List<UUID> findNextUnfinishedPodcastEpisodeId(@Param("podcastId") UUID podcastId,
                                                  @Param("userId") UUID userId,
                                                  @Param("afterEpisodeId") UUID afterEpisodeId);
}
