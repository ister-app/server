package app.ister.core.repository;

import app.ister.core.entitiy.LibraryEntity;
import app.ister.core.entitiy.MetadataEntity;
import app.ister.core.entitiy.MovieEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MovieRepository extends JpaRepository<MovieEntity, UUID> {
    Optional<MovieEntity> findByLibraryEntityAndNameAndReleaseYear(LibraryEntity libraryEntity, String name, int releaseYear);

    /**
     * Returns the IDs (UUID) of movies that have no {@link MetadataEntity} linked to them.
     */
    @Query("SELECT s.id FROM MovieEntity s LEFT JOIN s.metadataEntities m " +
            "WHERE m IS NULL")
    List<UUID> findIdsOfMoviesWithoutMetadata();
}
