package app.ister.core.repository;

import app.ister.core.entity.MediaFileEpisodeEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileEpisodeRepository extends CrudRepository<MediaFileEpisodeEntity, UUID> {

    List<MediaFileEpisodeEntity> findByMediaFileEntityIdOrderByPartNumber(UUID mediaFileEntityId);

    List<MediaFileEpisodeEntity> findByEpisodeEntityId(UUID episodeEntityId);

    Optional<MediaFileEpisodeEntity> findByMediaFileEntityIdAndEpisodeEntityId(UUID mediaFileEntityId, UUID episodeEntityId);

    void deleteAllByMediaFileEntityId(UUID mediaFileEntityId);
}
