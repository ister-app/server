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
     * Returns a page of episode IDs of a podcast, newest first (the natural play order of a
     * podcast queue is reverse-chronological).
     */
    @Query(value = """
            SELECT e.id FROM podcast_episode_entity e
            WHERE e.podcast_entity_id = :podcastId
            ORDER BY e.published_at DESC NULLS LAST, e.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findEpisodeIdsForPodcastOrdered(@Param("podcastId") UUID podcastId, @Param("limit") int limit, @Param("offset") int offset);
}
