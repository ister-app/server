package app.ister.core.repository;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrackRepository extends JpaRepository<TrackEntity, UUID> {
    Optional<TrackEntity> findByAlbumEntityAndNumberAndDiscNumber(AlbumEntity albumEntity, int number, int discNumber);
    List<TrackEntity> findByAlbumEntity_Id(UUID albumId, Sort sort);
    List<TrackEntity> findByAlbumEntity_LibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    /**
     * Returns a page of track IDs of an album in natural play order (disc number, track number).
     */
    @Query(value = """
            SELECT t.id FROM track_entity t
            WHERE t.album_entity_id = :albumId
            ORDER BY t.disc_number, t.number, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTrackIdsForAlbumOrdered(@Param("albumId") UUID albumId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * Returns a page of track IDs of an album in a deterministic shuffled order derived from the seed.
     */
    @Query(value = """
            SELECT t.id FROM track_entity t
            WHERE t.album_entity_id = :albumId AND t.id <> :excludeId
            ORDER BY md5(t.id::text || :seed), t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTrackIdsForAlbumShuffled(@Param("albumId") UUID albumId, @Param("seed") String seed, @Param("excludeId") UUID excludeId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * Returns a page of track IDs of a whole library in a deterministic shuffled order derived from the seed.
     */
    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            WHERE a.library_entity_id = :libraryId AND t.id <> :excludeId
            ORDER BY md5(t.id::text || :seed), t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTrackIdsForLibraryShuffled(@Param("libraryId") UUID libraryId, @Param("seed") String seed, @Param("excludeId") UUID excludeId, @Param("limit") int limit, @Param("offset") int offset);
}
