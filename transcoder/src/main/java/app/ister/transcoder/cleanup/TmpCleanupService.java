package app.ister.transcoder.cleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Removes transcode working directories ({@code tmpDir/{mediaFileId}/}) that no live consumer needs.
 * <p>
 * A transcode directory is pure derived cache — it can always be regenerated. Rather than expiring
 * on an arbitrary age, this reasons about whether the directory is still <em>in use</em>:
 * <ul>
 *   <li><b>orphan</b> — its media file no longer exists in the database → always removed;</li>
 *   <li>otherwise removed only when no FFmpeg pass is currently encoding it <em>and</em> it has been
 *       idle (no file written) for longer than {@code minAge}. The grace window protects a session
 *       that just paused after its file was fully transcoded, and covers the check-then-delete race.</li>
 * </ul>
 * Directories whose name is not a media-file UUID are left untouched.
 */
@Slf4j
@Component
public class TmpCleanupService {

    public record CleanupResult(long dirsDeleted, long bytesFreed, long dirsKept) {
    }

    public CleanupResult clean(Path tmpRoot,
                               Predicate<UUID> mediaFileExists,
                               Predicate<UUID> hasActivePass,
                               java.time.Clock clock,
                               java.time.Duration minAge,
                               boolean dryRun) throws IOException {
        if (!Files.isDirectory(tmpRoot)) {
            log.warn("Tmp cleanup: root {} does not exist, skipping", tmpRoot);
            return new CleanupResult(0, 0, 0);
        }
        java.time.Instant cutoff = clock.instant().minus(minAge);
        long deleted = 0;
        long bytes = 0;
        long kept = 0;
        List<Path> dirs;
        try (Stream<Path> s = Files.list(tmpRoot)) {
            dirs = s.filter(Files::isDirectory).toList();
        }
        for (Path dir : dirs) {
            String reason = deletionReason(dir, mediaFileExists, hasActivePass, cutoff);
            if (reason == null) {
                kept++;
            } else {
                long size = sizeOf(dir);
                if (dryRun) {
                    log.info("Tmp cleanup [dry-run] would delete {} transcode dir {} ({} bytes)", reason, dir, size);
                } else {
                    deleteRecursively(dir);
                    log.debug("Tmp cleanup deleted {} transcode dir {} ({} bytes)", reason, dir, size);
                }
                deleted++;
                bytes += size;
            }
        }
        return new CleanupResult(deleted, bytes, kept);
    }

    /**
     * Why this directory may be removed, or {@code null} when it must be kept.
     */
    private static String deletionReason(Path dir,
                                         Predicate<UUID> mediaFileExists,
                                         Predicate<UUID> hasActivePass,
                                         java.time.Instant cutoff) throws IOException {
        UUID mediaFileId = parseUuid(dir.getFileName().toString());
        if (mediaFileId == null) {
            return null;
        }
        if (!mediaFileExists.test(mediaFileId)) {
            return "orphan";
        }
        if (hasActivePass.test(mediaFileId) || lastActivity(dir).isAfter(cutoff)) {
            return null;
        }
        return "idle";
    }

    private static UUID parseUuid(String name) {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /** Most recent modification time across the directory tree (last segment written). */
    private static java.time.Instant lastActivity(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            java.time.Instant max = java.time.Instant.EPOCH;
            for (Path p : (Iterable<Path>) walk::iterator) {
                java.time.Instant m = Files.getLastModifiedTime(p).toInstant();
                if (m.isAfter(max)) {
                    max = m;
                }
            }
            return max;
        }
    }

    private static long sizeOf(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            long total = 0;
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p)) {
                    total += Files.size(p);
                }
            }
            return total;
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
