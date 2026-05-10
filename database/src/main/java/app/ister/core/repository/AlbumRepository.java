package app.ister.core.repository;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.ArtistEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlbumRepository extends JpaRepository<AlbumEntity, UUID> {
    Optional<AlbumEntity> findByArtistEntityAndNameAndReleaseYear(ArtistEntity artistEntity, String name, int releaseYear);

    Page<AlbumEntity> findByArtistEntity(ArtistEntity artistEntity, Pageable pageable);

    Page<AlbumEntity> findByLibraryEntityId(UUID libraryId, Pageable pageable);

    List<AlbumEntity> findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);
}
