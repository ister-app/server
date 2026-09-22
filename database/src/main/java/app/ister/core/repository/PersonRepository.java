package app.ister.core.repository;

import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonRepository extends JpaRepository<PersonEntity, UUID> {
    Optional<PersonEntity> findByLibraryEntityAndName(LibraryEntity libraryEntity, String name);

    /**
     * Person lookup by identity rather than spelling (see {@code PersonNames.normalize}). "First"
     * because duplicates from before the merge migration may still exist in older databases; the
     * oldest row is the one that owns the albums.
     */
    Optional<PersonEntity> findFirstByLibraryEntityAndNameNormalizedOrderByDateCreatedAsc(LibraryEntity libraryEntity, String nameNormalized);

    Optional<PersonEntity> findFirstByNameNormalizedAndLibraryEntityIsNullOrderByDateCreatedAsc(String nameNormalized);

    Page<PersonEntity> findByLibraryEntity(LibraryEntity libraryEntity, Pageable pageable);

    Page<PersonEntity> findByLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);

    List<PersonEntity> findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    List<PersonEntity> findByLibraryEntityId(UUID libraryId);

    Optional<PersonEntity> findByTmdbId(Long tmdbId);

    List<PersonEntity> findByNameAndBirthYear(String name, Integer birthYear);

    List<PersonEntity> findByNameAndBirthYearIsNull(String name);

    Optional<PersonEntity> findFirstByNameAndLibraryEntityIsNull(String name);

    /**
     * Artists of a music library that nothing refers to any more: no album, track or track credit,
     * no film credit, book, audiobook chapter or series. Left behind when a re-scan credits a track
     * to someone else — "A &amp; B" once stood for a duet that now credits A and B.
     */
    @Query("select p from PersonEntity p where p.libraryEntity.id = :libraryId"
            + " and p.libraryEntity.libraryType = app.ister.core.enums.LibraryType.MUSIC"
            + " and not exists (select a from AlbumEntity a where a.personEntity = p)"
            + " and not exists (select t from TrackEntity t where t.personEntity = p)"
            + " and not exists (select c from TrackCreditEntity c where c.personEntity = p)"
            + " and not exists (select c from CreditEntity c where c.personEntity = p)"
            + " and not exists (select b from BookEntity b where b.personEntity = p)"
            + " and not exists (select c from ChapterEntity c where c.personEntity = p)"
            + " and not exists (select s from SeriesEntity s where s.personEntity = p)")
    List<PersonEntity> findUnusedInMusicLibrary(@Param("libraryId") UUID libraryId);
}
