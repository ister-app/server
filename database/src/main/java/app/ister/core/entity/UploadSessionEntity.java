package app.ister.core.entity;

import app.ister.core.enums.UploadSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * One admin upload: a picked folder that lands in a library directory. The session lives in the
 * shared database so that the node receiving the chunks, a resuming client and the cleanup
 * scheduler all see the same state.
 */
@Entity
@Table(name = "upload_session")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UploadSessionEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private DirectoryEntity directoryEntity;

    /** The admin that started it; null once that user is removed. */
    @ManyToOne(fetch = FetchType.LAZY)
    private UserEntity userEntity;

    /** Relative path inside the directory the upload lands under; empty = the directory root. */
    @Column(nullable = false, length = 4096)
    private String targetParent;

    /** Name the picked folder gets on the server; null = its children become the roots. */
    private String rootName;

    @Column(nullable = false)
    private boolean overwrite;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UploadSessionStatus status;

    /** Moved forward by every chunk; the cleanup scheduler expires sessions on it. */
    @Column(nullable = false)
    private Instant lastActivityAt;
}
