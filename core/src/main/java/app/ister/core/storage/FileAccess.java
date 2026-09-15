package app.ister.core.storage;

import app.ister.core.entity.DirectoryEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/**
 * Storage-agnostic reads of a directory's file by its stored path: the local filesystem for a
 * LOCAL directory, the directory's {@link ObjectStore} for S3. For anything that needs a real
 * file (zip, pdf, external tools) use {@link LocalCopy} instead.
 */
@Component
@RequiredArgsConstructor
public class FileAccess {

    private final ObjectStoreRegistry registry;

    public boolean exists(DirectoryEntity directory, String path) {
        if (ObjectRef.isS3Uri(path)) {
            return registry.forDirectory(directory).stat(ObjectRef.parse(path).key()).isPresent();
        }
        return Files.exists(Path.of(path));
    }

    /** Size and timestamps; empty when the file is gone. S3 has no creation time: lastModified is used for both. */
    public Optional<ObjectStat> stat(DirectoryEntity directory, String path) throws IOException {
        if (ObjectRef.isS3Uri(path)) {
            return registry.forDirectory(directory).stat(ObjectRef.parse(path).key());
        }
        Path local = Path.of(path);
        if (!Files.exists(local)) {
            return Optional.empty();
        }
        BasicFileAttributes attrs = Files.readAttributes(local, BasicFileAttributes.class);
        return Optional.of(new ObjectStat(path, attrs.size(), attrs.lastModifiedTime().toInstant(), null, null));
    }

    /** Creation time where the storage has one, else the modification time. */
    public static java.time.Instant creationTime(String path, ObjectStat stat) throws IOException {
        if (!ObjectRef.isS3Uri(path)) {
            return Files.readAttributes(Path.of(path), BasicFileAttributes.class).creationTime().toInstant();
        }
        return stat.lastModified();
    }

    /** @return whether something was deleted */
    public boolean delete(DirectoryEntity directory, String path) throws IOException {
        if (ObjectRef.isS3Uri(path)) {
            return registry.forDirectory(directory).delete(ObjectRef.parse(path).key());
        }
        return Files.deleteIfExists(Path.of(path));
    }

    /** Caller closes. */
    public InputStream open(DirectoryEntity directory, String path) throws IOException {
        if (ObjectRef.isS3Uri(path)) {
            return registry.forDirectory(directory).open(ObjectRef.parse(path).key());
        }
        return Files.newInputStream(Path.of(path));
    }
}
