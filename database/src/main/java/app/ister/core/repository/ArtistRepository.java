package app.ister.core.repository;

import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArtistRepository extends JpaRepository<ArtistEntity, UUID> {
    Optional<ArtistEntity> findByLibraryEntityAndName(LibraryEntity libraryEntity, String name);

    Page<ArtistEntity> findByLibraryEntity(LibraryEntity libraryEntity, Pageable pageable);

    List<ArtistEntity> findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    List<ArtistEntity> findByLibraryEntityId(UUID libraryId);
}
