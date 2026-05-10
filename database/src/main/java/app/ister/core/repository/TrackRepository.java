package app.ister.core.repository;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrackRepository extends JpaRepository<TrackEntity, UUID> {
    Optional<TrackEntity> findByAlbumEntityAndNumberAndDiscNumber(AlbumEntity albumEntity, int number, int discNumber);
    List<TrackEntity> findByAlbumEntity_Id(UUID albumId, Sort sort);
    List<TrackEntity> findByAlbumEntity_LibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);
}
