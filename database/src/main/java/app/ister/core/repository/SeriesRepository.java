package app.ister.core.repository;

import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SeriesRepository extends JpaRepository<SeriesEntity, UUID> {
    Optional<SeriesEntity> findByPersonEntityAndName(PersonEntity personEntity, String name);

    /**
     * Orphan cleanup: a series whose last book was relinked (epub metadata changed) or deleted.
     */
    void deleteByBookEntitiesIsEmpty();
}
