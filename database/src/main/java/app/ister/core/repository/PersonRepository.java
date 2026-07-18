package app.ister.core.repository;

import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonRepository extends JpaRepository<PersonEntity, UUID> {
    Optional<PersonEntity> findByLibraryEntityAndName(LibraryEntity libraryEntity, String name);

    Page<PersonEntity> findByLibraryEntity(LibraryEntity libraryEntity, Pageable pageable);

    Page<PersonEntity> findByLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);

    List<PersonEntity> findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    List<PersonEntity> findByLibraryEntityId(UUID libraryId);

    Optional<PersonEntity> findByTmdbId(Long tmdbId);

    List<PersonEntity> findByNameAndBirthYear(String name, Integer birthYear);

    List<PersonEntity> findByNameAndBirthYearIsNull(String name);

    Optional<PersonEntity> findFirstByNameAndLibraryEntityIsNull(String name);
}
