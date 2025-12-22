package app.ister.server.repository;

import app.ister.server.entitiy.LibraryEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.entitiy.ShowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
    Optional<ShowEntity> findByLibraryEntityAndNameAndReleaseYear(LibraryEntity libraryEntity, String name, int releaseYear);


    /**
     * Returns the IDs (UUID) of shows that have no {@link MetadataEntity} linked to them.
     */
    @Query("SELECT s.id FROM ShowEntity s LEFT JOIN s.metadataEntities m " +
            "WHERE m IS NULL")
    List<UUID> findIdsOfShowsWithoutMetadata();
}
