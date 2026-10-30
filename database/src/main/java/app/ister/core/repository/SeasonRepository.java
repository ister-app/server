package app.ister.core.repository;

import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeasonRepository extends CrudRepository<SeasonEntity, UUID> {
    Optional<SeasonEntity> findByShowEntityAndNumber(ShowEntity showEntity, int number);

    /** Race-safe season create, see {@link ShowRepository#insertShowIfAbsent}. 1 = inserted, 0 = lost the race. */
    @Modifying
    @Query(value = """
            INSERT INTO season_entity (id, date_created, date_updated, show_entity_id, number)
            VALUES (:id, now(), now(), :showId, :number)
            ON CONFLICT (show_entity_id, number) DO NOTHING
            """, nativeQuery = true)
    int insertSeasonIfAbsent(@Param("id") UUID id,
                             @Param("showId") UUID showId,
                             @Param("number") int number);

    List<SeasonEntity> findByShowEntityId(UUID tvShow, Sort sort);
}
