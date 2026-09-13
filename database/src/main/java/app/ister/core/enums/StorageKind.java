package app.ister.core.enums;

/**
 * Where a directory's files live. {@code LOCAL} is a filesystem path on exactly one owning node;
 * {@code S3} is a bucket prefix that any number of nodes can attach to (see
 * {@code DirectoryEntity.attachedNodes}).
 */
public enum StorageKind {
    LOCAL,
    S3
}
