package app.ister.core.repository;

import app.ister.core.entity.CreditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CreditRepository extends CrudRepository<CreditEntity, UUID>, PagingAndSortingRepository<CreditEntity, UUID> {
    void deleteByMovieEntityId(UUID movieEntityId);

    void deleteByShowEntityId(UUID showEntityId);

    void deleteByEpisodeEntityId(UUID episodeEntityId);

    List<CreditEntity> findByMovieEntityIdIn(Collection<UUID> movieEntityIds);

    List<CreditEntity> findByShowEntityIdIn(Collection<UUID> showEntityIds);

    List<CreditEntity> findByEpisodeEntityIdIn(Collection<UUID> episodeEntityIds);

    List<CreditEntity> findByPersonEntityId(UUID personEntityId, Sort sort);

    Page<CreditEntity> findByMovieEntityId(UUID movieEntityId, Pageable pageable);

    Page<CreditEntity> findByShowEntityId(UUID showEntityId, Pageable pageable);

    Page<CreditEntity> findByEpisodeEntityId(UUID episodeEntityId, Pageable pageable);
}
