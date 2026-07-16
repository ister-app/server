package app.ister.core.repository;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeriesRepository extends JpaRepository<SeriesEntity, UUID> {
    Optional<SeriesEntity> findByPersonEntityAndName(PersonEntity personEntity, String name);

    /** Comic series identity: the library + the series directory name + its "(YYYY)" year. */
    Optional<SeriesEntity> findByLibraryEntityAndNameAndStartYear(LibraryEntity libraryEntity, String name, int startYear);

    Page<SeriesEntity> findByLibraryEntityId(UUID libraryId, Pageable pageable);

    List<SeriesEntity> findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    /**
     * Orphan cleanup: a series whose last book was relinked (epub metadata changed) or deleted.
     */
    void deleteByBookEntitiesIsEmpty();
}
