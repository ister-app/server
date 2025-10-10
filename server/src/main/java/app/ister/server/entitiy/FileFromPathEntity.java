package app.ister.server.entitiy;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * This class is extended by the other entities so al entities hava a:
 * -
 */
@EqualsAndHashCode(callSuper = true)
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@Data
@SuperBuilder
@EntityListeners(AuditingEntityListener.class)
public class FileFromPathEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "directory_entity_id", referencedColumnName = "id", insertable = false, updatable = false)
    private DirectoryEntity directoryEntity;

    @Column(name = "directory_entity_id", nullable = false)
    private UUID directoryEntityId;

    private Instant fileCreationTime;
    private Instant fileLastModifiedTime;

    @Column(nullable = false)
    private String path;

    // Setter method for directoryEntity
    public void setDirectoryEntity(DirectoryEntity directoryEntity) {
        this.directoryEntity = directoryEntity;
        this.directoryEntityId = (directoryEntity != null) ? directoryEntity.getId() : null;
    }
}
