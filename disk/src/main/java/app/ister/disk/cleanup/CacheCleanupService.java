package app.ister.disk.cleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Removes zombie files from a node's image cache directory: regular files on disk whose absolute
 * path is not referenced by any {@code ImageEntity} or {@code MediaFileStreamEntity} row.
 * <p>
 * A grace window ({@code minAge}) protects freshly written files. Images are written to disk
 * <em>before</em> their {@code IMAGE_FOUND} event is processed (see {@code ImageDownloadService}),
 * so a just-downloaded file legitimately has no DB row for a short while; deleting only files
 * older than {@code minAge} avoids racing that window.
 */
@Slf4j
@Component
public class CacheCleanupService {

    public record CleanupResult(long filesDeleted, long bytesFreed, long filesKept) {
    }

    private final Clock clock;

    public CacheCleanupService() {
        this(Clock.systemUTC());
    }

    CacheCleanupService(Clock clock) {
        this.clock = clock;
    }

    public CleanupResult clean(Path cacheRoot, Set<String> referencedPaths, Duration minAge, boolean dryRun)
            throws IOException {
        if (!Files.isDirectory(cacheRoot)) {
            log.warn("Cache cleanup: root {} does not exist, skipping", cacheRoot);
            return new CleanupResult(0, 0, 0);
        }
        Instant cutoff = clock.instant().minus(minAge);
        long deleted = 0;
        long bytes = 0;
        long kept = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(cacheRoot)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            if (referencedPaths.contains(file.toString())) {
                kept++;
                continue;
            }
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (attrs.lastModifiedTime().toInstant().isAfter(cutoff)) {
                // Too recent: could be an in-flight download whose IMAGE_FOUND event is still queued.
                kept++;
                continue;
            }
            long size = attrs.size();
            if (dryRun) {
                log.info("Cache cleanup [dry-run] would delete zombie {} ({} bytes)", file, size);
            } else {
                Files.delete(file);
                log.debug("Cache cleanup deleted zombie {} ({} bytes)", file, size);
            }
            deleted++;
            bytes += size;
        }
        return new CleanupResult(deleted, bytes, kept);
    }
}
