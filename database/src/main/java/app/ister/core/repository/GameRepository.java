package app.ister.core.repository;

import app.ister.core.entity.GameEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.enums.GamePlatform;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<GameEntity, UUID> {

    /** Scanner identity: (library, platform, name, path year). */
    Optional<GameEntity> findByLibraryEntityAndPlatformAndNameAndPathYear(
            LibraryEntity libraryEntity, GamePlatform platform, String name, int pathYear);

    /** Folder-form games are also unique on their game folder. */
    Optional<GameEntity> findByDirectoryEntityIdAndRootPath(UUID directoryEntityId, String rootPath);

    List<GameEntity> findByDirectoryEntityId(UUID directoryEntityId);

    Page<GameEntity> findByLibraryEntityId(UUID libraryId, Pageable pageable);

    Page<GameEntity> findByLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);

    Page<GameEntity> findByLibraryEntityIdAndPlatform(UUID libraryId, GamePlatform platform, Pageable pageable);

    Page<GameEntity> findByLibraryEntityIdInAndPlatform(Collection<UUID> libraryIds, GamePlatform platform, Pageable pageable);

    Page<GameEntity> findByPlatform(GamePlatform platform, Pageable pageable);

    long countByLibraryEntityId(UUID libraryId);

    /** Games without any artwork row: the image refresh looks for covers for these. */
    @Query("select g from GameEntity g where g.imageEntities is empty")
    List<GameEntity> findWithoutImages();

    @Query("select g.id from GameEntity g where g.libraryEntity.id = :libraryId")
    List<UUID> findIdsByLibraryEntityId(@Param("libraryId") UUID libraryId);
}
