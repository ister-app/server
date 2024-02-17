package app.ister.server.repository;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.SeasonEntity;
import app.ister.server.entitiy.ShowEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EpisodeRepository extends JpaRepository<EpisodeEntity, UUID> {

    Optional<EpisodeEntity> findByShowEntityAndSeasonEntityAndNumber(ShowEntity showEntity, SeasonEntity seasonEntity, int number);

    Page<EpisodeEntity> findAll(Pageable pageable);

    List<EpisodeEntity> findBySeasonEntityId(UUID season);
}
