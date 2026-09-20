package app.ister.core.entity;

import app.ister.core.enums.UploadFileStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One target file of an {@link UploadSessionEntity}, assembled from chunks. */
// The partial unique key on target_path (active files only) lives in the migration: JPA cannot
// express a partial index.
@Entity
@Table(name = "upload_file")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UploadFileEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private UploadSessionEntity uploadSession;

    /** Path relative to the folder the admin picked: how a resuming client finds its local file again. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String relativePath;

    /** Same format as {@link MediaFileEntity#getPath()}: absolute for LOCAL, {@code s3://bucket/key} for S3. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String targetPath;

    @Column(nullable = false)
    private long size;

    /** Fixed per file at session creation, so an offset always maps to the same S3 part number. */
    @Column(nullable = false)
    private long chunkSize;

    /** Contiguous bytes from offset 0 for LOCAL; the sum of the stored parts for S3. */
    @Column(nullable = false)
    private long receivedBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UploadFileStatus status;

    /** The S3 multipart upload this file is assembled in; null for LOCAL directories. */
    @Column(length = 1024)
    private String s3UploadId;

    /** Two chunk requests for one file must not both advance {@link #receivedBytes}. */
    @Version
    @Column(nullable = false)
    private long version;
}
