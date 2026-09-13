package app.ister.core.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link TmpStore} on a bucket prefix: {@code prefix/{mediaFileId}/{fileName}}. */
public class S3TmpStore implements TmpStore {

    private final ObjectStore store;
    private final String prefix;

    public S3TmpStore(ObjectStore store, String prefix) {
        this.store = store;
        String p = prefix == null ? "" : prefix;
        this.prefix = p.isEmpty() ? "" : p + "/";
    }

    private String key(UUID mediaFileId, String fileName) {
        return prefix + mediaFileId + "/" + fileName;
    }

    @Override
    public Optional<ObjectStat> stat(UUID mediaFileId, String fileName) {
        return store.stat(key(mediaFileId, fileName));
    }

    @Override
    public boolean copyToLocal(UUID mediaFileId, String fileName, Path target) throws IOException {
        try {
            store.copyToLocal(key(mediaFileId, fileName), target);
            return true;
        } catch (java.nio.file.NoSuchFileException _) {
            return false;
        }
    }

    @Override
    public void put(UUID mediaFileId, Path file) throws IOException {
        String name = file.getFileName().toString();
        String contentType = name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
                : name.endsWith(".ts") ? "video/mp2t" : null;
        store.put(key(mediaFileId, name), file, contentType);
    }

    @Override
    public List<ObjectStat> list(UUID mediaFileId) {
        String dir = prefix + mediaFileId + "/";
        return store.listShallow(dir).objects().stream()
                .map(o -> new ObjectStat(o.key().substring(dir.length()), o.size(), o.lastModified(), o.etag(), o.contentType()))
                .toList();
    }

    @Override
    public List<UUID> mediaFileIds() {
        return store.listShallow(prefix).childPrefixes().stream()
                .map(p -> p.substring(prefix.length()))
                .map(name -> {
                    try {
                        return UUID.fromString(name);
                    } catch (IllegalArgumentException _) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<Instant> lastActivity(UUID mediaFileId) {
        return list(mediaFileId).stream().map(ObjectStat::lastModified).filter(java.util.Objects::nonNull).max(Instant::compareTo);
    }

    @Override
    public void deleteAll(UUID mediaFileId) throws IOException {
        for (ObjectStat object : list(mediaFileId)) {
            store.delete(key(mediaFileId, object.key()));
        }
    }
}
