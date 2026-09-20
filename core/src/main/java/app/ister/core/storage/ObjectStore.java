package app.ister.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The bytes behind an S3 directory. One instance per configured connection, bound to one bucket;
 * keys are bucket-relative (no leading slash). Paths stored in the database are
 * {@code s3://bucket/key} ({@link ObjectRef}); {@link #uri(String)} / {@link ObjectRef#parse}
 * convert.
 */
public interface ObjectStore {

    String bucket();

    /** Every object under {@code prefix}, recursively, lazily paginated. Close the stream. */
    Stream<ObjectStat> list(String prefix);

    /**
     * One level: the objects directly under {@code prefix} and the child "directories" (common
     * prefixes, returned without trailing slash). {@code prefix} must end with {@code /} or be empty.
     */
    Listing listShallow(String prefix);

    Optional<ObjectStat> stat(String key);

    /** The whole object. Caller closes. */
    InputStream open(String key) throws IOException;

    /** Bytes {@code from..toInclusive}; {@code toInclusive < 0} means "to the end". Caller closes. */
    RangedObject openRange(String key, long from, long toInclusive) throws IOException;

    void put(String key, Path file, String contentType) throws IOException;

    void put(String key, InputStream body, long length, String contentType) throws IOException;

    /**
     * Starts assembling {@code key} from parts: the way to write an object whose total size exceeds
     * a single put (5 GB) or that arrives in pieces over time. Nothing is visible under {@code key}
     * until {@link #completeMultipartUpload}.
     *
     * @return the upload id the other multipart calls need
     */
    String createMultipartUpload(String key, String contentType) throws IOException;

    /**
     * Stores part {@code partNumber} (1-based); sending a number again replaces that part. Every part
     * but the last must be at least 5 MiB. The body is streamed, so a failed call cannot be retried
     * by the store itself: the caller sends the part again.
     *
     * @return the part's ETag, needed to complete the upload
     */
    String uploadPart(String key, String uploadId, int partNumber, InputStream body, long length) throws IOException;

    void completeMultipartUpload(String key, String uploadId, List<UploadedPart> parts) throws IOException;

    /**
     * Discards the upload and its stored parts; a no-op when it is already gone. Whoever starts an
     * upload must remember its key and id to be able to do this: listing pending uploads is no
     * substitute, MinIO for one only answers that for an exact object key.
     */
    void abortMultipartUpload(String key, String uploadId) throws IOException;

    /** @return whether an object was deleted */
    boolean delete(String key) throws IOException;

    void copyToLocal(String key, Path target) throws IOException;

    /** A URL that grants read access to the object for {@code ttl} without further credentials. */
    String presignGet(String key, Duration ttl);

    default String uri(String key) {
        return new ObjectRef(bucket(), key).uri();
    }

    record Listing(List<ObjectStat> objects, List<String> childPrefixes) {
    }

    record UploadedPart(int partNumber, String etag) {
    }

}
