package app.ister.core.repository;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlbumRepository extends JpaRepository<AlbumEntity, UUID> {
    Optional<AlbumEntity> findByPersonEntityAndNameAndReleaseYear(PersonEntity personEntity, String name, int releaseYear);

    /**
     * Used when the album directory carries no "(YYYY)" suffix: the year is then unknown rather than
     * zero, so it cannot take part in matching. Oldest first, so the album that already holds the
     * tracks wins over any later row with the same name.
     */
    Optional<AlbumEntity> findFirstByPersonEntityAndNameOrderByDateCreatedAsc(PersonEntity personEntity, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AlbumEntity a WHERE a.id = :id")
    Optional<AlbumEntity> findByIdForUpdate(@Param("id") UUID id);

    Page<AlbumEntity> findByPersonEntity(PersonEntity personEntity, Pageable pageable);

    Page<AlbumEntity> findByPersonEntityAndLibraryEntityIdIn(PersonEntity personEntity, Collection<UUID> libraryIds, Pageable pageable);

    Page<AlbumEntity> findByLibraryEntityId(UUID libraryId, Pageable pageable);

    Page<AlbumEntity> findByLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);

    List<AlbumEntity> findByPersonEntityId(UUID personId);

    List<AlbumEntity> findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    /**
     * The calling user's most recently played albums of a library, newest first, aggregated over
     * the tracks' watch rows. Track watch rows only exist for real plays (the 30-second threshold
     * is applied when they are written), so no progress predicate is needed here.
     */
    @Query(value = """
            SELECT a.id FROM album_entity a
            JOIN track_entity t ON t.album_entity_id = a.id
            JOIN watch_status_entity ws ON ws.track_entity_id = t.id
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE a.library_entity_id = :libraryId
            GROUP BY a.id
            ORDER BY MAX(ws.date_updated) DESC, a.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findRecentlyPlayedAlbumIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit);

    /** The calling user's most played albums of a library (track plays). */
    @Query(value = """
            SELECT a.id FROM album_entity a
            JOIN track_entity t ON t.album_entity_id = a.id
            JOIN watch_status_entity ws ON ws.track_entity_id = t.id
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE a.library_entity_id = :libraryId
            GROUP BY a.id
            ORDER BY COUNT(ws.id) DESC, MAX(ws.date_updated) DESC, a.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findMostPlayedAlbumIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit);

    /** The calling user's highest rated albums of a library; the most recently (re)rated wins ties. */
    @Query(value = """
            SELECT a.id FROM album_entity a
            JOIN rating_entity r ON r.album_entity_id = a.id
            JOIN user_entity u ON u.id = r.user_entity_id AND u.external_id = :externalId
            WHERE a.library_entity_id = :libraryId
            ORDER BY r.value DESC, r.date_updated DESC, a.id
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findHighestRatedAlbumIdsForLibrary(@Param("libraryId") UUID libraryId, @Param("externalId") String externalId, @Param("limit") int limit);

    List<AlbumEntity> findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType libraryType);
}
