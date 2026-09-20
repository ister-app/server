package app.ister.core.enums;

public enum UploadSessionStatus {
    /** Accepting chunks. */
    ACTIVE,
    /** Every file reached a final state. */
    COMPLETED,
    /** Cancelled by the admin; staged bytes are removed. */
    ABORTED,
    /** Idle for longer than the session timeout; cleaned up by the scheduler. */
    EXPIRED
}
