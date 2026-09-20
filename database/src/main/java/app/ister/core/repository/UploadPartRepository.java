package app.ister.core.repository;

import app.ister.core.entity.UploadPartEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UploadPartRepository extends CrudRepository<UploadPartEntity, UUID> {
    List<UploadPartEntity> findByUploadFileIdOrderByPartNumber(UUID uploadFileId);

    @Query("SELECT COALESCE(SUM(p.size), 0) FROM UploadPartEntity p WHERE p.uploadFile.id = :uploadFileId")
    long sumSizeByUploadFileId(@Param("uploadFileId") UUID uploadFileId);

    /**
     * Stores one part. A native upsert rather than a find-then-save: a client retrying a chunk whose
     * response got lost sends the same part again, and that must replace the part, not fail on
     * (upload_file_id, part_number).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO upload_part (id, date_created, date_updated, upload_file_id, part_number, etag, size)
            VALUES (gen_random_uuid(), now(), now(), :uploadFileId, :partNumber, :etag, :size)
            ON CONFLICT (upload_file_id, part_number) DO UPDATE SET
                date_updated = now(),
                etag = EXCLUDED.etag,
                size = EXCLUDED.size""",
            nativeQuery = true)
    void upsert(@Param("uploadFileId") UUID uploadFileId,
                @Param("partNumber") int partNumber,
                @Param("etag") String etag,
                @Param("size") long size);
}
