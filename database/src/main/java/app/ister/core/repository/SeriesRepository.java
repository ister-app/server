package app.ister.core.repository;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeriesRepository extends JpaRepository<SeriesEntity, UUID> {
    Optional<SeriesEntity> findByPersonEntityAndName(PersonEntity personEntity, String name);

    /** All series of one author — the candidate set for Wikidata series discovery. */
    List<SeriesEntity> findByPersonEntityId(UUID personId);

    /** Comic series identity: the library + the series directory name + its "(YYYY)" year. */
    Optional<SeriesEntity> findByLibraryEntityAndNameAndStartYear(LibraryEntity libraryEntity, String name, int startYear);

    Page<SeriesEntity> findByLibraryEntityId(UUID libraryId, Pageable pageable);

    List<SeriesEntity> findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    /**
     * Orphan cleanup: a series whose last book was relinked (epub metadata changed) or deleted.
     */
    void deleteByBookEntitiesIsEmpty();

    /**
     * Race-safe comic series create: parallel ComicFileFound consumers scanning one series
     * directory all try to create the same row, so a find-then-save loses and poisons the whole
     * transaction on the {@code series_entity_comic_identity} unique index. Returns 1 when this
     * call inserted the row, 0 when a concurrent transaction beat it to it.
     */
    @Modifying
    @Query(value = """
            INSERT INTO series_entity (id, date_created, date_updated, library_entity_id, name, start_year)
            VALUES (:id, now(), now(), :libraryId, :name, :startYear)
            ON CONFLICT (library_entity_id, name, start_year) WHERE person_entity_id IS NULL DO NOTHING
            """, nativeQuery = true)
    int insertComicSeriesIfAbsent(@Param("id") UUID id,
                                  @Param("libraryId") UUID libraryId,
                                  @Param("name") String name,
                                  @Param("startYear") int startYear);
}
