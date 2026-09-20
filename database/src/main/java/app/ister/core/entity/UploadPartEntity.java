package app.ister.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A stored part of an S3 multipart upload; completing the upload needs every part's ETag. */
// The unique key on (upload_file_id, part_number) lives in the migration: it is the conflict
// target of UploadPartRepository.upsert.
@Entity
@Table(name = "upload_part")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UploadPartEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private UploadFileEntity uploadFile;

    /** 1-based, as S3 numbers them. */
    @Column(nullable = false)
    private int partNumber;

    @Column(nullable = false)
    private String etag;

    @Column(nullable = false)
    private long size;
}
