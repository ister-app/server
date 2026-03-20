package app.ister.core.repository;

import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeasonRepository extends CrudRepository<SeasonEntity, UUID> {
    Optional<SeasonEntity> findByShowEntityAndNumber(ShowEntity showEntity, int number);

    List<SeasonEntity> findByShowEntityId(UUID tvShow, Sort sort);
}
