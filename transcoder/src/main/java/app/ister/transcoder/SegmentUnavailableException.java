package app.ister.transcoder;

import java.io.IOException;

/**
 * A segment that a completed pass did not write, and never will.
 * <p>
 * Distinct from the I/O errors around it because the answer to the client is
 * different: a timeout or a failed pass is worth retrying, this is not. Served
 * as a plain 503 it told clients "try again" forever — the pass is finished, it
 * is not started again, and a downloading client can retry for hours without
 * ever getting the file.
 * <p>
 * Stays an {@link IOException} so every caller and signature around it is
 * unchanged; only the exception handler tells them apart.
 */
public class SegmentUnavailableException extends IOException {
    public SegmentUnavailableException(String message) {
        super(message);
    }
}
