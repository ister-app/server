package app.ister.core.storage;

import app.ister.core.config.S3Properties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.FileFromPathEntity;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One {@link ObjectStore} per configured S3 connection, built at startup. Everything that reads
 * or writes an S3 directory resolves its store here by the directory's connection name.
 *
 * <p>The clients are built eagerly and unconditionally (no {@code @ConditionalOnProperty}: bean
 * conditions are frozen into the native image), so a node without S3 config simply has an empty
 * registry.
 */
@Slf4j
@Component
public class ObjectStoreRegistry {

    private final Map<String, S3ObjectStore> stores = new LinkedHashMap<>();

    public ObjectStoreRegistry(S3Properties properties) {
        for (S3Properties.Connection connection : properties.getConnections()) {
            if (connection.getName() == null || connection.getName().isBlank()) {
                throw new IllegalStateException("app.ister.s3.connections[n].name is required");
            }
            if (connection.getBucket() == null || connection.getBucket().isBlank()) {
                throw new IllegalStateException("app.ister.s3.connections[" + connection.getName() + "].bucket is required");
            }
            if (stores.containsKey(connection.getName())) {
                throw new IllegalStateException("Duplicate S3 connection name " + connection.getName());
            }
            stores.put(connection.getName(), new S3ObjectStore(connection));
            log.info("S3 connection '{}': bucket {} at {}", connection.getName(), connection.getBucket(),
                    connection.isAws() ? "AWS " + connection.getRegion() : connection.getEndpoint());
        }
    }

    public boolean isEmpty() {
        return stores.isEmpty();
    }

    public Optional<ObjectStore> byConnection(String name) {
        return Optional.ofNullable(stores.get(name));
    }

    /** The store for an S3 directory; throws when this node has no connection of that name. */
    public ObjectStore forDirectory(DirectoryEntity directory) {
        if (!directory.isS3()) {
            throw new IllegalArgumentException("Directory " + directory.getName() + " is not an S3 directory");
        }
        return byConnection(directory.getS3Connection()).orElseThrow(() -> new IllegalStateException(
                "Directory " + directory.getName() + " uses S3 connection '" + directory.getS3Connection()
                        + "' which this node does not configure (app.ister.s3.connections)"));
    }

    /**
     * The store serving an {@code s3://bucket/...} uri, matched on bucket: for rows that carry no
     * directory of their own (an extracted subtitle's stream row) — buckets are unique per cluster.
     */
    public Optional<ObjectStore> forUri(String uri) {
        if (!ObjectRef.isS3Uri(uri)) {
            return Optional.empty();
        }
        String bucket = ObjectRef.parse(uri).bucket();
        return stores.values().stream().filter(s -> s.bucket().equals(bucket)).map(s -> (ObjectStore) s).findFirst();
    }

    public ObjectStore forEntity(FileFromPathEntity entity) {
        return forDirectory(entity.getDirectoryEntity());
    }

    /** Bucket-relative key of an entity's {@code s3://} path. */
    public static String keyOf(FileFromPathEntity entity) {
        return ObjectRef.parse(entity.getPath()).key();
    }

    @PreDestroy
    void close() {
        stores.values().forEach(S3ObjectStore::close);
    }
}
