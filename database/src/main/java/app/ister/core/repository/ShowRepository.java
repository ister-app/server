package app.ister.core.repository;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.ShowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Returns the IDs (UUID) of shows that have no {@link MetadataEntity} linked to them
     * and have at least one episode with a media file on the given node.
     */
    @Query("SELECT DISTINCT s.id FROM ShowEntity s LEFT JOIN s.metadataEntities m " +
            "WHERE m IS NULL AND s.id IN (" +
            "SELECT DISTINCT ep.showEntity.id FROM EpisodeEntity ep " +
            "JOIN ep.mediaFileEntities mf JOIN mf.directoryEntity d " +
            "WHERE d.nodeEntity.name = :nodeName)")
    List<UUID> findIdsOfShowsWithoutMetadataForNode(@Param("nodeName") String nodeName);
}
