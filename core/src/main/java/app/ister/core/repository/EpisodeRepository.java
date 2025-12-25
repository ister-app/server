package app.ister.core.repository;

import app.ister.core.entitiy.EpisodeEntity;
import app.ister.core.entitiy.MetadataEntity;
import app.ister.core.entitiy.SeasonEntity;
import app.ister.core.entitiy.ShowEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EpisodeRepository extends JpaRepository<EpisodeEntity, UUID> {

    Optional<EpisodeEntity> findByShowEntityAndSeasonEntityAndNumber(ShowEntity showEntity, SeasonEntity seasonEntity, int number);

    Page<EpisodeEntity> findAll(Pageable pageable);

    List<EpisodeEntity> findBySeasonEntityIdOrderByNumberAsc(UUID season);

    List<IdOnly> findIdsOnlyByShowEntityId(UUID season, Sort sort);

    List<EpisodeEntity> findByShowEntityId(UUID season, Sort sort);

    /**
     * Returns the IDs (UUID) of episodes that have no {@link MetadataEntity} linked to them.
     */
    @Query("SELECT s.id FROM EpisodeEntity s LEFT JOIN s.metadataEntities m " +
            "WHERE m IS NULL")
    List<UUID> findIdsOfEpisodesWithoutMetadata();

    interface IdOnly {

        UUID getId();
    }
}
