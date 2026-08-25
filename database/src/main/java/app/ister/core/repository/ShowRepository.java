package app.ister.core.repository;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.ShowEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
    Optional<ShowEntity> findByLibraryEntityAndNameAndReleaseYear(LibraryEntity libraryEntity, String name, int releaseYear);

    Page<ShowEntity> findByLibraryEntity(LibraryEntity libraryEntity, Pageable pageable);

    Page<ShowEntity> findByLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);


    @Query("SELECT s.id FROM ShowEntity s WHERE s.libraryEntity.id = :libraryId")
    List<UUID> findIdsByLibraryId(@Param("libraryId") UUID libraryId);

    /**
     * The calling user's most recently played shows of a library, newest first, aggregated over
     * the episodes' watch rows. An episode's watch row is created the moment playback starts, so
     * only rows marked watched or past two minutes count as a play.
     */
    @Query(value = """
            SELECT s.id FROM show_entity s
            JOIN episode_entity e ON e.show_entity_id = s.id
            JOIN watch_status_entity ws ON ws.episode_entity_id = e.id
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE s.library_entity_id = :libraryId
              AND (ws.watched OR ws.progress_in_milliseconds >= 120000)
            GROUP BY s.id
            ORDER BY MAX(ws.date_updated) DESC, s.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findRecentlyPlayedShowIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit, @Param("offset") int offset);

    /** Total for the recently/most played pages: same play filter as the two queries above. */
    @Query(value = """
            SELECT COUNT(DISTINCT s.id) FROM show_entity s
            JOIN episode_entity e ON e.show_entity_id = s.id
            JOIN watch_status_entity ws ON ws.episode_entity_id = e.id
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE s.library_entity_id = :libraryId
              AND (ws.watched OR ws.progress_in_milliseconds >= 120000)""", nativeQuery = true)
    long countPlayedShowsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId);

    /** The calling user's most played shows of a library (episode plays); threshold as above. */
    @Query(value = """
            SELECT s.id FROM show_entity s
            JOIN episode_entity e ON e.show_entity_id = s.id
            JOIN watch_status_entity ws ON ws.episode_entity_id = e.id
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE s.library_entity_id = :libraryId
              AND (ws.watched OR ws.progress_in_milliseconds >= 120000)
            GROUP BY s.id
            ORDER BY COUNT(ws.id) DESC, MAX(ws.date_updated) DESC, s.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findMostPlayedShowIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit, @Param("offset") int offset);

    /** The calling user's highest rated shows of a library; the most recently (re)rated wins ties. */
    @Query(value = """
            SELECT s.id FROM show_entity s
            JOIN rating_entity r ON r.show_entity_id = s.id
            JOIN user_entity u ON u.id = r.user_entity_id AND u.external_id = :externalId
            WHERE s.library_entity_id = :libraryId
            ORDER BY r.value DESC, r.date_updated DESC, s.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findHighestRatedShowIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit, @Param("offset") int offset);

    /** Total for the highest rated page: same rating join as the query above. */
    @Query(value = """
            SELECT COUNT(DISTINCT s.id) FROM show_entity s
            JOIN rating_entity r ON r.show_entity_id = s.id
            JOIN user_entity u ON u.id = r.user_entity_id AND u.external_id = :externalId
            WHERE s.library_entity_id = :libraryId""", nativeQuery = true)
    long countRatedShowsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId);

    /**
     * Returns the IDs (UUID) of shows the metadata backfill should re-dispatch: shows without any
     * {@link MetadataEntity} row, and shows whose TMDB enrichment columns were never filled
     * (tmdbId is set by every successful TMDB fetch, so a null one marks a pre-V45 show exactly
     * once). Optionally scoped to one library.
     */
    @Query("SELECT DISTINCT s.id FROM ShowEntity s LEFT JOIN s.metadataEntities m " +
            "WHERE (m IS NULL OR s.tmdbId IS NULL) " +
            "AND (:libraryId IS NULL OR s.libraryEntity.id = :libraryId)")
    List<UUID> findIdsOfShowsNeedingMetadata(@Param("libraryId") UUID libraryId);
}
