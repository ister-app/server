package app.ister.core.repository;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlbumRepository extends JpaRepository<AlbumEntity, UUID> {
    Optional<AlbumEntity> findByPersonEntityAndNameAndReleaseYear(PersonEntity personEntity, String name, int releaseYear);

    /**
     * Used when the album directory carries no "(YYYY)" suffix: the year is then unknown rather than
     * zero, so it cannot take part in matching. Oldest first, so the album that already holds the
     * tracks wins over any later row with the same name.
     */
    Optional<AlbumEntity> findFirstByPersonEntityAndNameOrderByDateCreatedAsc(PersonEntity personEntity, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AlbumEntity a WHERE a.id = :id")
    Optional<AlbumEntity> findByIdForUpdate(@Param("id") UUID id);

    Page<AlbumEntity> findByPersonEntity(PersonEntity personEntity, Pageable pageable);

    Page<AlbumEntity> findByPersonEntityAndLibraryEntityIdIn(PersonEntity personEntity, Collection<UUID> libraryIds, Pageable pageable);

    Page<AlbumEntity> findByLibraryEntityId(UUID libraryId, Pageable pageable);

    Page<AlbumEntity> findByLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);

    List<AlbumEntity> findByPersonEntityId(UUID personId);

    List<AlbumEntity> findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    List<AlbumEntity> findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType libraryType);
}
