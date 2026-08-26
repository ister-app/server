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
     * Shows of the same library that resemble the given show, best match first.
     *
     * <p>The score is derived from the metadata the TMDB enrichment already stores: shared
     * keywords weigh heaviest (3.0 each, capped at five so a single long keyword list cannot
     * dominate), then shared genres (1.5 each), then shared cast members (1.0 each, capped at
     * three). Network, origin country and a release year within five years add half a point each,
     * but only rank shows that already share content: a common broadcast year on its own is not a
     * relation. A show that shares nothing is left out entirely, so a show without TMDB enrichment
     * simply yields an empty list instead of arbitrary neighbours.
     *
     * <p>Keywords/networks/countries are the comma-space joined strings written by
     * {@code TmdbFieldUtil.joinNonBlank}; genres live per language on the metadata rows and are
     * therefore compared within one language (the best-matching language wins), since the genre
     * names themselves are translated.
     */
    @Query(value = """
            WITH me AS (
                SELECT s.id,
                       s.library_entity_id,
                       s.release_year,
                       array_remove(string_to_array(lower(coalesce(s.keywords, '')), ', '), '') AS kw,
                       array_remove(string_to_array(lower(coalesce(s.networks, '')), ', '), '') AS nets,
                       array_remove(string_to_array(lower(coalesce(s.origin_country, '')), ', '), '') AS countries
                FROM show_entity s
                WHERE s.id = :showId
            ),
            candidate AS (
                SELECT s.id,
                       s.name,
                       s.vote_average,
                       s.release_year,
                       array_remove(string_to_array(lower(coalesce(s.keywords, '')), ', '), '') AS kw,
                       array_remove(string_to_array(lower(coalesce(s.networks, '')), ', '), '') AS nets,
                       array_remove(string_to_array(lower(coalesce(s.origin_country, '')), ', '), '') AS countries
                FROM show_entity s
                CROSS JOIN me
                WHERE s.library_entity_id = me.library_entity_id
                  AND s.id <> me.id
            ),
            genre_overlap AS (
                SELECT other.show_entity_id AS show_id,
                       MAX(cardinality(ARRAY(
                           SELECT unnest(array_remove(string_to_array(lower(mine.genre), ', '), ''))
                           INTERSECT
                           SELECT unnest(array_remove(string_to_array(lower(other.genre), ', '), ''))))) AS shared
                FROM metadata_entity mine
                JOIN me ON mine.show_entity_id = me.id
                JOIN metadata_entity other ON other.language = mine.language
                                          AND other.show_entity_id IS NOT NULL
                                          AND other.show_entity_id <> me.id
                WHERE mine.genre IS NOT NULL AND other.genre IS NOT NULL
                GROUP BY other.show_entity_id
            ),
            cast_overlap AS (
                SELECT theirs.show_entity_id AS show_id,
                       COUNT(DISTINCT theirs.person_entity_id) AS shared
                FROM credit_entity ours
                JOIN me ON ours.show_entity_id = me.id
                JOIN credit_entity theirs ON theirs.person_entity_id = ours.person_entity_id
                                         AND theirs.show_entity_id IS NOT NULL
                                         AND theirs.show_entity_id <> me.id
                GROUP BY theirs.show_entity_id
            ),
            scored AS (
                SELECT c.id,
                       c.name,
                       c.vote_average,
                       3.0 * least(cardinality(ARRAY(SELECT unnest(c.kw) INTERSECT SELECT unnest(me.kw))), 5)
                         + 1.5 * coalesce(g.shared, 0)
                         + 1.0 * least(coalesce(ca.shared, 0), 3) AS content_score,
                       CASE WHEN c.nets && me.nets THEN 0.5 ELSE 0 END
                         + CASE WHEN c.countries && me.countries THEN 0.5 ELSE 0 END
                         + CASE WHEN abs(c.release_year - me.release_year) <= 5 THEN 0.5 ELSE 0 END AS context_score
                FROM candidate c
                CROSS JOIN me
                LEFT JOIN genre_overlap g ON g.show_id = c.id
                LEFT JOIN cast_overlap ca ON ca.show_id = c.id
            )
            SELECT id FROM scored
            WHERE content_score > 0
            ORDER BY content_score + context_score DESC, vote_average DESC NULLS LAST, name ASC, id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findRelatedShowIds(@Param("showId") UUID showId, @Param("limit") int limit);

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
