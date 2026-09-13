package app.ister.core.storage;

import app.ister.core.entity.DirectoryEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/** {@link CacheStore} on a bucket prefix: stored paths are {@code s3://bucket/prefix/key}. */
public class S3CacheStore implements CacheStore {

    private final DirectoryEntity directory;
    private final ObjectStore store;
    private final String prefix;

    public S3CacheStore(DirectoryEntity directory, ObjectStore store) {
        this.directory = directory;
        this.store = store;
        String p = directory.getS3Prefix() == null ? "" : directory.getS3Prefix();
        this.prefix = p.isEmpty() ? "" : p + "/";
    }

    @Override
    public DirectoryEntity directory() {
        return directory;
    }

    private String keyFor(String relativeKey) {
        return prefix + (relativeKey.startsWith("/") ? relativeKey.substring(1) : relativeKey);
    }

    private static String keyOf(String storedPath) {
        return ObjectRef.parse(storedPath).key();
    }

    @Override
    public String write(String relativeKey, Path file, String contentType) throws IOException {
        String key = keyFor(relativeKey);
        store.put(key, file, contentType);
        Files.deleteIfExists(file);
        return store.uri(key);
    }

    @Override
    public String write(String relativeKey, InputStream body, long length, String contentType) throws IOException {
        String key = keyFor(relativeKey);
        store.put(key, body, length, contentType);
        return store.uri(key);
    }

    @Override
    public boolean exists(String storedPath) {
        return stat(storedPath).isPresent();
    }

    @Override
    public Optional<ObjectStat> stat(String storedPath) {
        return store.stat(keyOf(storedPath))
                .map(s -> new ObjectStat(storedPath, s.size(), s.lastModified(), s.etag(), s.contentType()));
    }

    @Override
    public boolean delete(String storedPath) throws IOException {
        return store.delete(keyOf(storedPath));
    }

    @Override
    public Stream<ObjectStat> list() {
        return store.list(prefix).map(s -> new ObjectStat(store.uri(s.key()), s.size(), s.lastModified(), s.etag(), s.contentType()));
    }
}
