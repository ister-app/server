package app.ister.core.repository;

import app.ister.core.entity.MediaFileSegmentEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MediaFileSegmentRepository extends CrudRepository<MediaFileSegmentEntity, UUID> {

    List<MediaFileSegmentEntity> findByMediaFileEntityId(UUID mediaFileEntityId);

    List<MediaFileSegmentEntity> findByMediaFileEntityIdIn(Collection<UUID> mediaFileEntityIds);

    void deleteAllByMediaFileEntityId(UUID mediaFileEntityId);
}
