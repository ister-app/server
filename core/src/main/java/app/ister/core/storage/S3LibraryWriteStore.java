package app.ister.core.storage;

import app.ister.core.entity.DirectoryEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * {@link LibraryWriteStore} on an S3 directory: a file is one multipart upload, a chunk is one
 * part. Nothing is visible under the target key until the upload completes, so there is no staging
 * folder; what an abandoned session leaves behind are unfinished multipart uploads, which
 * {@link #abort} discards — the caller keeps the upload id for exactly that.
 */
public class S3LibraryWriteStore implements LibraryWriteStore {

    private final DirectoryEntity directory;
    private final ObjectStore store;

    public S3LibraryWriteStore(DirectoryEntity directory, ObjectStore store) {
        this.directory = directory;
        this.store = store;
    }

    @Override
    public DirectoryEntity directory() {
        return directory;
    }

    private static String keyOf(String targetPath) {
        return ObjectRef.parse(targetPath).key();
    }

    @Override
    public boolean exists(String targetPath) {
        return store.stat(keyOf(targetPath)).isPresent();
    }

    @Override
    public Staged begin(UUID sessionId, UUID fileId, String targetPath, long size) throws IOException {
        // An empty file has no part to upload, and S3 refuses to complete an upload without parts.
        String uploadId = size == 0 ? null : store.createMultipartUpload(keyOf(targetPath), null);
        return new Staged(sessionId, fileId, targetPath, uploadId);
    }

    @Override
    public String writeChunk(Staged staged, long offset, int partNumber, InputStream body, long length)
            throws IOException {
        if (staged.uploadId() == null) {
            throw new IOException("No multipart upload for " + staged.targetPath());
        }
        return store.uploadPart(keyOf(staged.targetPath()), staged.uploadId(), partNumber, body, length);
    }

    @Override
    public void complete(Staged staged, List<ObjectStore.UploadedPart> parts, long expectedSize, boolean overwrite)
            throws IOException {
        String key = keyOf(staged.targetPath());
        if (!overwrite && store.stat(key).isPresent()) {
            throw new FileAlreadyExistsException(staged.targetPath());
        }
        if (staged.uploadId() == null) {
            store.put(key, InputStream.nullInputStream(), 0, null);
            return;
        }
        try {
            store.completeMultipartUpload(key, staged.uploadId(), parts);
        } catch (IOException e) {
            // A retry: the upload was already completed, only the caller's bookkeeping did not make it.
            if (store.stat(key).map(ObjectStat::size).orElse(-1L) != expectedSize) {
                throw e;
            }
        }
        long stored = store.stat(key).map(ObjectStat::size).orElse(-1L);
        if (stored != expectedSize) {
            store.delete(key);
            throw new IOException("Assembled " + stored + " bytes for " + staged.targetPath()
                    + ", expected " + expectedSize);
        }
    }

    @Override
    public void abort(Staged staged) throws IOException {
        if (staged.uploadId() != null) {
            store.abortMultipartUpload(keyOf(staged.targetPath()), staged.uploadId());
        }
    }

    @Override
    public void abortSession(UUID sessionId) {
        // nothing session-scoped to remove: every file's multipart upload is aborted on its own
    }

    @Override
    public OptionalLong usableSpace() {
        return OptionalLong.empty();
    }

    @Override
    public boolean writable() {
        // Bucket permissions only show on the first write; a probe object would cost a request per
        // directory listing.
        return true;
    }
}
