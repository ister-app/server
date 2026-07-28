package app.ister.core.repository;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MovieRepository extends JpaRepository<MovieEntity, UUID> {
    Optional<MovieEntity> findByLibraryEntityAndNameAndReleaseYear(LibraryEntity libraryEntity, String name, int releaseYear);

    Page<MovieEntity> findByLibraryEntity(LibraryEntity libraryEntity, Pageable pageable);

    Page<MovieEntity> findByLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);

    @Query("SELECT m.id FROM MovieEntity m WHERE m.libraryEntity.id = :libraryId")
    List<UUID> findIdsByLibraryId(@Param("libraryId") UUID libraryId);

    /**
     * Returns a page of movie IDs of a whole library in a deterministic shuffled order derived from the seed.
     */
    @Query(value = """
            SELECT m.id FROM movie_entity m
            WHERE m.library_entity_id = :libraryId AND m.id <> :excludeId
            ORDER BY md5(m.id::text || :seed), m.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findMovieIdsForLibraryShuffled(@Param("libraryId") UUID libraryId, @Param("seed") String seed, @Param("excludeId") UUID excludeId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * The calling user's most recently played movies of a library, newest first. A movie's watch
     * row is created the moment playback starts, so only rows marked watched or past two minutes
     * count as a play — an abandoned start must not surface the movie here.
     */
    @Query(value = """
            SELECT m.id FROM movie_entity m
            JOIN watch_status_entity ws ON ws.movie_entity_id = m.id
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE m.library_entity_id = :libraryId
              AND (ws.watched OR ws.progress_in_milliseconds >= 120000)
            GROUP BY m.id
            ORDER BY MAX(ws.date_updated) DESC, m.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findRecentlyPlayedMovieIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit);

    /** The calling user's most played movies of a library; see the played threshold above. */
    @Query(value = """
            SELECT m.id FROM movie_entity m
            JOIN watch_status_entity ws ON ws.movie_entity_id = m.id
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE m.library_entity_id = :libraryId
              AND (ws.watched OR ws.progress_in_milliseconds >= 120000)
            GROUP BY m.id
            ORDER BY COUNT(ws.id) DESC, MAX(ws.date_updated) DESC, m.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findMostPlayedMovieIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit);

    /** The calling user's highest rated movies of a library; the most recently (re)rated wins ties. */
    @Query(value = """
            SELECT m.id FROM movie_entity m
            JOIN rating_entity r ON r.movie_entity_id = m.id
            JOIN user_entity u ON u.id = r.user_entity_id AND u.external_id = :externalId
            WHERE m.library_entity_id = :libraryId
            ORDER BY r.value DESC, r.date_updated DESC, m.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findHighestRatedMovieIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit);

    /**
     * Returns the IDs (UUID) of movies that have no {@link MetadataEntity} linked to them.
     */
    @Query("SELECT s.id FROM MovieEntity s LEFT JOIN s.metadataEntities m " +
            "WHERE m IS NULL")
    List<UUID> findIdsOfMoviesWithoutMetadata();

    /**
     * Returns the IDs (UUID) of movies that have no {@link MetadataEntity} linked to them
     * and have a media file on the given node.
     */
    @Query("SELECT DISTINCT mv.id FROM MovieEntity mv LEFT JOIN mv.metadataEntities m " +
            "JOIN mv.mediaFileEntities mf JOIN mf.directoryEntity d " +
            "WHERE m IS NULL AND d.nodeEntity.name = :nodeName")
    List<UUID> findIdsOfMoviesWithoutMetadataForNode(@Param("nodeName") String nodeName);
}
