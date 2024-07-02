package app.ister.server.repository;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MediaFileEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileRepository extends CrudRepository<MediaFileEntity, UUID> {

    Optional<MediaFileEntity> findByDirectoryEntityAndEpisodeEntityAndPath(DirectoryEntity directoryEntity, EpisodeEntity episodeEntity, String path);
    List<MediaFileEntity> findByDirectoryEntity(DirectoryEntity directoryEntity);
}
