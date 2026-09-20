package app.ister.core.storage;

import app.ister.core.entity.DirectoryEntity;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Writes files INTO a library directory, assembled from chunks that arrive over time: the write
 * counterpart of {@link FileAccess}, and what {@link CacheStore} is for derived files. A file only
 * appears at its target path once {@link #complete} ran; until then its bytes are staged where no
 * scanner looks.
 *
 * <p>Implementations keep no state of their own. The caller (the upload session rows) remembers
 * how far a file is and hands that back in through {@link Staged} and the offsets.
 */
public interface LibraryWriteStore {

    /** Hidden folder under a LOCAL directory's root that holds staged bytes; dot-prefixed, so both scan walkers skip it. */
    String STAGING_DIR = ".ister-upload";

    DirectoryEntity directory();

    boolean exists(String targetPath);

    /**
     * Prepares staging for one file.
     *
     * @return the handle the other calls need; its {@code uploadId} is what has to be remembered
     */
    Staged begin(UUID sessionId, UUID fileId, String targetPath, long size) throws IOException;

    /**
     * Stores {@code length} bytes of {@code body} that belong at {@code offset}. The caller has
     * already established that {@code offset} is where this file continues; bytes a previous,
     * broken-off call left behind beyond it are discarded.
     *
     * @param partNumber 1-based index of this chunk ({@code offset / chunkSize + 1})
     * @return the part's ETag for S3, {@code null} for LOCAL
     * @throws IOException when fewer than {@code length} bytes arrived; nothing of the chunk counts then
     */
    String writeChunk(Staged staged, long offset, int partNumber, InputStream body, long length) throws IOException;

    /**
     * Makes the file appear at its target path. Safe to call again after it succeeded: a caller whose
     * own commit failed afterwards retries, and finds the work done.
     *
     * @throws java.nio.file.FileAlreadyExistsException when the target exists and {@code overwrite} is off
     * @throws IOException                              when the staged bytes do not add up to {@code expectedSize}
     */
    void complete(Staged staged, List<ObjectStore.UploadedPart> parts, long expectedSize, boolean overwrite)
            throws IOException;

    /** Discards what was staged for one file; a no-op when nothing was. */
    void abort(Staged staged) throws IOException;

    /** Discards whatever a session left in staging that {@link #abort} did not cover. */
    void abortSession(UUID sessionId) throws IOException;

    /** Bytes that can still be written; empty when the storage has no meaningful limit (S3). */
    OptionalLong usableSpace();

    /** Whether this node can create files here at all (media mounts are often read-only). */
    boolean writable();

    /**
     * @param uploadId the S3 multipart upload id; {@code null} for LOCAL and for empty files
     */
    record Staged(UUID sessionId, UUID fileId, String targetPath, String uploadId) {
    }
}
