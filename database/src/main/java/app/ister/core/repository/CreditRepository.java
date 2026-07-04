package app.ister.core.repository;

import app.ister.core.entity.CreditEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CreditRepository extends CrudRepository<CreditEntity, UUID> {
    void deleteByMovieEntityId(UUID movieEntityId);

    void deleteByShowEntityId(UUID showEntityId);

    void deleteByEpisodeEntityId(UUID episodeEntityId);

    List<CreditEntity> findByMovieEntityIdIn(Collection<UUID> movieEntityIds);

    List<CreditEntity> findByShowEntityIdIn(Collection<UUID> showEntityIds);

    List<CreditEntity> findByEpisodeEntityIdIn(Collection<UUID> episodeEntityIds);

    List<CreditEntity> findByPersonEntityId(UUID personEntityId, Sort sort);
}
