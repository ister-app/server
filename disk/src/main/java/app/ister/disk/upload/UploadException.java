package app.ister.disk.upload;

import org.springframework.http.HttpStatus;

/**
 * An upload request the server understood and refuses; carries the status the client acts on
 * (409 resync, 429 back off, 507 free up space). Kept local to the upload endpoints on purpose:
 * mapping a general exception type to these codes would change every other endpoint too.
 */
public class UploadException extends RuntimeException {

    private final HttpStatus status;
    private final transient Object body;

    public UploadException(HttpStatus status, String message) {
        this(status, message, null);
    }

    /** @param body sent instead of a problem detail, for a client that needs data to recover (the offset to continue at) */
    public UploadException(HttpStatus status, String message, Object body) {
        super(message);
        this.status = status;
        this.body = body;
    }

    public HttpStatus status() {
        return status;
    }

    public Object body() {
        return body;
    }
}
