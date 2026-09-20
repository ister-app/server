package app.ister.core.enums;

public enum UploadFileStatus {
    /** Registered, no bytes received yet. */
    PENDING,
    UPLOADING,
    /** Moved to its target path and handed to the scanner. */
    COMPLETED,
    /** Not uploaded: the target exists (and overwrite is off) or the scanner would ignore it. */
    SKIPPED,
    FAILED;

    /** Whether the file still claims its target path. */
    public boolean isActive() {
        return this == PENDING || this == UPLOADING;
    }
}
