package app.ister.core.repository;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.StorageKind;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DirectoryRepository extends CrudRepository<DirectoryEntity, UUID> {
    Optional<DirectoryEntity> findByName(String name);

    List<DirectoryEntity> findByDirectoryTypeAndNodeEntity(DirectoryType directoryType, NodeEntity nodeEntity);

    List<DirectoryEntity> findByNodeEntity(NodeEntity nodeEntity);

    List<DirectoryEntity> findByDirectoryType(DirectoryType directoryType);

    List<DirectoryEntity> findByLibraryEntityAndDirectoryType(LibraryEntity libraryEntity, DirectoryType directoryType);

    List<DirectoryEntity> findByDirectoryTypeAndLibraryEntityId(DirectoryType directoryType, UUID libraryEntityId);

    List<DirectoryEntity> findByStorageKind(StorageKind storageKind);

    /** Directories this node can serve: its own LOCAL ones plus the S3 ones it attached to. */
    @Query("SELECT d FROM DirectoryEntity d JOIN d.attachedNodes n WHERE n = :node")
    List<DirectoryEntity> findAttachedTo(@Param("node") NodeEntity node);

    @Query("SELECT d FROM DirectoryEntity d JOIN d.attachedNodes n WHERE n = :node AND d.directoryType = :type")
    List<DirectoryEntity> findAttachedTo(@Param("node") NodeEntity node, @Param("type") DirectoryType type);

    @Query("SELECT n FROM DirectoryEntity d JOIN d.attachedNodes n WHERE d.id = :directoryId")
    List<NodeEntity> findAttachedNodes(@Param("directoryId") UUID directoryId);

    /**
     * Transaction-scoped advisory lock guarding one directory scan. Two scan requests for the same
     * directory (an operator double-clicking, or a scheduled and a manual scan overlapping) would
     * otherwise race on the same rows; the loser drops its message. Released on commit/rollback.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:namespace, hashtext(CAST(:directoryId AS text)))",
            nativeQuery = true)
    boolean tryLockDirectoryScan(@Param("namespace") int namespace, @Param("directoryId") UUID directoryId);
}
