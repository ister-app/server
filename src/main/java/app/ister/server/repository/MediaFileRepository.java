package app.ister.server.repository;

import app.ister.server.entitiy.*;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface MediaFileRepository extends CrudRepository<MediaFileEntity, UUID> {

    Optional<MediaFileEntity> findByDiskEntityAndEpisodeEntityAndPath(DiskEntity diskEntity, EpisodeEntity episodeEntity, String path);
}
