package app.ister.core.entity;

import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.StorageKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class DirectoryEntity extends BaseEntity {

    /** The owning node for {@link StorageKind#LOCAL}; {@code null} for S3 directories, which have no owner. */
    @ManyToOne
    private NodeEntity nodeEntity;

    @ManyToOne
    private LibraryEntity libraryEntity;

    @Column(nullable = false, unique = true)
    private String name;

    /** Absolute filesystem path for LOCAL; {@code s3://bucket/prefix} for S3. */
    @Column(nullable = false)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DirectoryType directoryType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private StorageKind storageKind = StorageKind.LOCAL;

    /** Name of the {@code app.ister.s3.connections[n]} entry every attached node must configure. */
    private String s3Connection;

    private String s3Bucket;

    /** Key prefix without leading or trailing slash; empty/null = bucket root. */
    private String s3Prefix;

    /**
     * Nodes that can serve this directory: the owner for LOCAL, every node that configured the
     * directory for S3. Never used for queue routing (that is config-derived); it feeds
     * validation, the cluster UI and the worker fan-outs that used to go to the single owner.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "directory_node",
            joinColumns = @JoinColumn(name = "directory_entity_id"),
            inverseJoinColumns = @JoinColumn(name = "node_entity_id"))
    @Builder.Default
    private Set<NodeEntity> attachedNodes = new HashSet<>();

    public boolean isS3() {
        return storageKind == StorageKind.S3;
    }
}
