package app.ister.core.storage;

import app.ister.core.config.S3Properties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.FileFromPathEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A local file for code that cannot read a stream: {@code ZipFile} (epub/cbz) and PDFBox.
 * For a LOCAL directory the entity's own path is handed back untouched; for
 * S3 the object is downloaded once into {@code app.ister.s3.local-copy-dir} and kept as an LRU
 * cache (size cap {@code local-copy-max-bytes}), so an epub the reader keeps paging through is
 * fetched once, not per request.
 *
 * <pre>
 * try (LocalCopy.Handle h = localCopy.of(mediaFile)) { parse(h.path()); }
 * </pre>
 * Closing the handle releases the in-use pin; it never deletes the copy.
 */
@Slf4j
@Component
public class LocalCopy {

    private final ObjectStoreRegistry registry;
    private final Path scratchDir;
    private final long maxBytes;
    private final Map<Path, Pin> inUse = new ConcurrentHashMap<>();

    public LocalCopy(ObjectStoreRegistry registry, S3Properties properties,
                     @Value("${app.ister.server.tmp-dir}") String tmpDir) {
        this.registry = registry;
        String dir = properties.getLocalCopyDir();
        this.scratchDir = dir == null || dir.isBlank() ? Path.of(tmpDir, "s3-scratch") : Path.of(dir);
        this.maxBytes = properties.getLocalCopyMaxBytes();
    }

    /**
     * The local path of an entity's file for request handlers that read it right away and
     * cannot hold a handle across a streamed response: for LOCAL the path itself, for S3 the
     * cached copy (downloaded now if needed); {@code null} when the object does not exist. The
     * copy is not pinned — it was just touched, so the LRU sweep takes it last.
     */
    public Path localPathOrNull(FileFromPathEntity entity) throws IOException {
        try (Handle handle = of(entity)) {
            return handle.path();
        } catch (java.nio.file.NoSuchFileException _) {
            return null;
        }
    }

    public Handle of(FileFromPathEntity entity) throws IOException {
        return of(entity.getDirectoryEntity(), entity.getPath());
    }

    public Handle of(DirectoryEntity directory, String path) throws IOException {
        if (!ObjectRef.isS3Uri(path)) {
            return new Handle(Path.of(path), null);
        }
        ObjectRef ref = ObjectRef.parse(path);
        Path target = scratchDir.resolve(sha256(path)).resolve(ref.fileName());
        Pin pin = inUse.computeIfAbsent(target, _ -> new Pin());
        pin.count.incrementAndGet();
        try {
            if (Files.isRegularFile(target)) {
                Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
            } else {
                synchronized (pin) {
                    if (!Files.isRegularFile(target)) {
                        log.debug("Downloading {} to {}", path, target);
                        registry.forDirectory(directory).copyToLocal(ref.key(), target);
                    }
                }
            }
            return new Handle(target, pin);
        } catch (IOException | RuntimeException e) {
            release(target, pin);
            throw e;
        }
    }

    private void release(Path target, Pin pin) {
        if (pin != null && pin.count.decrementAndGet() <= 0) {
            inUse.remove(target, pin);
        }
    }

    /** Drops the least recently used copies until the scratch dir fits the cap. Pinned files stay. */
    @Scheduled(fixedDelayString = "${app.ister.s3.local-copy-sweep-interval:PT10M}")
    public void sweep() {
        if (!Files.isDirectory(scratchDir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(scratchDir)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(this::lastModified))
                    .toList();
        } catch (IOException e) {
            log.warn("Cannot sweep {}: {}", scratchDir, e.getMessage());
            return;
        }
        long total = files.stream().mapToLong(this::size).sum();
        for (Path file : files) {
            if (total <= maxBytes) {
                break;
            }
            if (!inUse.containsKey(file) && !file.toString().endsWith(".part")) {
                long size = size(file);
                try {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(file.getParent());
                    total -= size;
                } catch (IOException e) {
                    log.debug("Cannot delete {}: {}", file, e.getMessage());
                }
            }
        }
    }

    private Instant lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException _) {
            return Instant.EPOCH;
        }
    }

    private long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException _) {
            return 0;
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reference count plus the lock a concurrent download of the same object waits on. */
    private static final class Pin {
        private final AtomicInteger count = new AtomicInteger();
    }

    public final class Handle implements AutoCloseable {
        private final Path path;
        private final Pin pin;

        private Handle(Path path, Pin pin) {
            this.path = path;
            this.pin = pin;
        }

        public Path path() {
            return path;
        }

        @Override
        public void close() {
            release(path, pin);
        }
    }
}
