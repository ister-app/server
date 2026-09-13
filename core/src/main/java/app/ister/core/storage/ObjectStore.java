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
}
