package app.ister.core.repository;

import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlaylistRepository extends JpaRepository<PlaylistEntity, UUID> {
    List<PlaylistEntity> findByUserEntityOrderByNameAsc(UserEntity userEntity);

    List<PlaylistEntity> findByUserEntityAndLibraryEntityIdInOrderByNameAsc(UserEntity userEntity, Collection<UUID> libraryIds);
}
