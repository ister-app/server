package app.ister.core.repository;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MovieRepository extends JpaRepository<MovieEntity, UUID> {
    Optional<MovieEntity> findByLibraryEntityAndNameAndReleaseYear(LibraryEntity libraryEntity, String name, int releaseYear);

    Page<MovieEntity> findByLibraryEntity(LibraryEntity libraryEntity, Pageable pageable);

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
