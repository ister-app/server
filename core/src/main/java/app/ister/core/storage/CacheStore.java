package app.ister.core.storage;

import app.ister.core.entity.DirectoryEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Writes into a CACHE directory without knowing whether it is a local directory or a bucket
 * prefix. Every derived file (background stills, covers, downloaded artwork, extracted subtitles,
 * podcast downloads) goes through here; the returned <em>stored path</em> is what the row records
 * and what every reader resolves again through the directory's storage kind.
 *
 * <p>Stored paths are always {@code directory.path + "/" + relativeKey}, for both kinds, so a
 * helper node can predict the owner's path for an upload it hands over.
 */
public interface CacheStore {

    DirectoryEntity directory();

    /** The stored path a relative key would get, without writing anything. */
    default String pathFor(String relativeKey) {
        return PathStrings.join(directory().getPath(), relativeKey);
    }

    /** Moves/uploads a finished local file into the cache; the source is consumed. */
    String write(String relativeKey, Path file, String contentType) throws IOException;

    String write(String relativeKey, InputStream body, long length, String contentType) throws IOException;

    default String write(String relativeKey, byte[] bytes, String contentType) throws IOException {
        return write(relativeKey, new java.io.ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    boolean exists(String storedPath);

    Optional<ObjectStat> stat(String storedPath);

    boolean delete(String storedPath) throws IOException;

    /** Every file/object in the cache, recursively; keys are stored paths. Close the stream. */
    Stream<ObjectStat> list() throws IOException;
}
