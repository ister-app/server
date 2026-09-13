package app.ister.core.storage;

import app.ister.core.entity.DirectoryEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.stream.Stream;

/** {@link CacheStore} on a node-local cache directory: files land under {@code directory.path}. */
public class LocalCacheStore implements CacheStore {

    private final DirectoryEntity directory;

    public LocalCacheStore(DirectoryEntity directory) {
        this.directory = directory;
    }

    @Override
    public DirectoryEntity directory() {
        return directory;
    }

    @Override
    public String write(String relativeKey, Path file, String contentType) throws IOException {
        Path target = Path.of(pathFor(relativeKey));
        Files.createDirectories(target.getParent());
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException _) {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toString();
    }

    @Override
    public String write(String relativeKey, InputStream body, long length, String contentType) throws IOException {
        Path target = Path.of(pathFor(relativeKey));
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + target.getFileName() + ".part-" + java.util.UUID.randomUUID());
        try {
            Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException _) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target.toString();
    }

    @Override
    public boolean exists(String storedPath) {
        return Files.isRegularFile(Path.of(storedPath));
    }

    @Override
    public Optional<ObjectStat> stat(String storedPath) {
        Path p = Path.of(storedPath);
        try {
            if (!Files.isRegularFile(p)) {
                return Optional.empty();
            }
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            return Optional.of(new ObjectStat(storedPath, attrs.size(), attrs.lastModifiedTime().toInstant(), null, null));
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String storedPath) throws IOException {
        return Files.deleteIfExists(Path.of(storedPath));
    }

    @Override
    public Stream<ObjectStat> list() throws IOException {
        Path root = Path.of(directory.getPath());
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        return Files.walk(root).filter(Files::isRegularFile).map(p -> stat(p.toString()).orElse(null)).filter(java.util.Objects::nonNull);
    }
}
