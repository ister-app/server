package app.ister.core.repository;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileStreamRepository extends JpaRepository<MediaFileStreamEntity, UUID> {

    Optional<MediaFileStreamEntity> findByMediaFileEntity(MediaFileEntity mediaFileEntity);

    boolean existsByMediaFileEntityAndStreamIndexAndPath(MediaFileEntity mediaFileEntity, int streamIndex, String path);

    List<MediaFileStreamEntity> findByMediaFileEntity_IdAndCodecType(UUID mediaFileId, StreamCodecType codecType);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM media_file_stream_entity WHERE media_file_entity_id = :mediaFileEntityId", nativeQuery = true)
    void deleteAllByMediaFileEntityId(@Param("mediaFileEntityId") UUID mediaFileEntityId);

    /** Bundles the columns of a single {@code media_file_stream_entity} row for {@link #upsert}. */
    record StreamUpsert(String codecName, String codecType, int height, String language,
                        UUID mediaFileEntityId, String path, int streamIndex, String title, int width) {
    }

    @Modifying
    @Query(value = """
            INSERT INTO media_file_stream_entity
                (id, codec_name, codec_type, date_created, date_updated, height, language,
                 media_file_entity_id, path, stream_index, title, width)
            VALUES
                (gen_random_uuid(), :#{#s.codecName}, :#{#s.codecType}, now(), now(), :#{#s.height}, :#{#s.language},
                 :#{#s.mediaFileEntityId}, :#{#s.path}, :#{#s.streamIndex}, :#{#s.title}, :#{#s.width})
            ON CONFLICT (media_file_entity_id, stream_index, path) DO UPDATE SET
                codec_name = EXCLUDED.codec_name,
                codec_type = EXCLUDED.codec_type,
                date_updated = now()
            """, nativeQuery = true)
    void upsert(@Param("s") StreamUpsert s);
}
