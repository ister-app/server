package app.ister.disk.upload;

import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * The server process may not write a file or folder it needs. Not transient — retrying does not
     * change file ownership — so a 409 the client gives up on at once, with a message an admin can
     * act on instead of the bare path {@link AccessDeniedException#getMessage()} is.
     */
    public static UploadException notWritable(AccessDeniedException e) {
        String path = e.getFile();
        return new UploadException(HttpStatus.CONFLICT, "The server has no permission to write " + path
                + ownerOf(path) + ". Fix the ownership or permissions of that folder on the server.");
    }

    private static String ownerOf(String path) {
        if (path == null) {
            return "";
        }
        try {
            return " (owned by " + Files.getOwner(Path.of(path)).getName() + ")";
        } catch (IOException | RuntimeException _) {
            return "";
        }
    }
}
