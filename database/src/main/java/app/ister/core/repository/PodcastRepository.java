package app.ister.core.repository;

import app.ister.core.entity.PodcastEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PodcastRepository extends JpaRepository<PodcastEntity, UUID> {
    Optional<PodcastEntity> findByFeedUrl(String feedUrl);

    Page<PodcastEntity> findByLibraryEntityIdAndActiveTrue(UUID libraryId, Pageable pageable);

    Page<PodcastEntity> findByLibraryEntityIdInAndActiveTrue(Collection<UUID> libraryIds, Pageable pageable);

    Page<PodcastEntity> findByActiveTrue(Pageable pageable);

    List<PodcastEntity> findByActiveTrue();

    /**
     * The calling user's most recently played podcasts of a library, newest first, aggregated over
     * the episodes' watch rows. An episode's watch row is created the moment playback starts, so
     * only rows marked watched or past two minutes count as a play. Inactive (unsubscribed)
     * podcasts stay out, matching the listing queries.
     */
    @Query(value = """
            SELECT p.id FROM podcast_entity p
            JOIN podcast_episode_entity pe ON pe.podcast_entity_id = p.id
            JOIN watch_status_entity ws ON ws.podcast_episode_entity_id = pe.id
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE p.library_entity_id = :libraryId AND p.active
              AND (ws.watched OR ws.progress_in_milliseconds >= 120000)
            GROUP BY p.id
            ORDER BY MAX(ws.date_updated) DESC, p.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findRecentlyPlayedPodcastIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit);

    /** The calling user's most played podcasts of a library (episode plays); threshold as above. */
    @Query(value = """
            SELECT p.id FROM podcast_entity p
            JOIN podcast_episode_entity pe ON pe.podcast_entity_id = p.id
            JOIN watch_status_entity ws ON ws.podcast_episode_entity_id = pe.id
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE p.library_entity_id = :libraryId AND p.active
              AND (ws.watched OR ws.progress_in_milliseconds >= 120000)
            GROUP BY p.id
            ORDER BY COUNT(ws.id) DESC, MAX(ws.date_updated) DESC, p.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findMostPlayedPodcastIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit);

    /** The calling user's highest rated podcasts of a library; the most recently (re)rated wins ties. */
    @Query(value = """
            SELECT p.id FROM podcast_entity p
            JOIN rating_entity r ON r.podcast_entity_id = p.id
            JOIN user_entity u ON u.id = r.user_entity_id AND u.external_id = :externalId
            WHERE p.library_entity_id = :libraryId AND p.active
            ORDER BY r.value DESC, r.date_updated DESC, p.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findHighestRatedPodcastIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit);
}
