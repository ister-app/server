package app.ister.disk.cleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prunes the downscaled-artwork cache. Two reasons to delete a thumbnail: nobody has requested it
 * in {@code maxIdle} (the cache touches every hit, so idle means genuinely cold), or the image it
 * derives from is gone from the database. Both are safe — a thumbnail is regenerated on the next
 * request for one decode.
 * <p>
 * Nothing else sweeps this tree: it lives under tmp-dir behind a non-UUID directory name, which
 * the transcode tmp sweep deliberately leaves alone.
 */
@Slf4j
@Service
public class ImageThumbnailCleanupService {

    public record CleanupResult(long filesDeleted, long bytesFreed, long filesKept) {
    }

    private final Clock clock;

    @Autowired
    public ImageThumbnailCleanupService() {
        this(Clock.systemUTC());
    }

    ImageThumbnailCleanupService(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param liveImageIds given every image id found on disk, returns the subset that still exists
     *                     in the database — one query per sweep, not one per file
     */
    public CleanupResult clean(Path thumbnailRoot, Duration maxIdle,
                               Function<Set<UUID>, Set<UUID>> liveImageIds, boolean dryRun)
            throws IOException {
        if (!Files.isDirectory(thumbnailRoot)) {
            log.debug("Thumbnail cleanup: {} does not exist, skipping", thumbnailRoot);
            return new CleanupResult(0, 0, 0);
        }
        Instant cutoff = clock.instant().minus(maxIdle);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(thumbnailRoot)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        Set<UUID> live = liveImageIds.apply(files.stream()
                .map(ImageThumbnailCleanupService::imageIdOf)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet()));

        long deleted = 0;
        long bytes = 0;
        long kept = 0;
        for (Path file : files) {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            String reason = deletionReason(file, attrs, cutoff, live);
            if (reason == null) {
                kept++;
                continue;
            }
            long size = attrs.size();
            if (dryRun) {
                log.info("Thumbnail cleanup [dry-run] would delete {} ({}, {} bytes)", file, reason, size);
            } else {
                Files.delete(file);
                log.debug("Thumbnail cleanup deleted {} ({}, {} bytes)", file, reason, size);
            }
            deleted++;
            bytes += size;
        }
        if (!dryRun) {
            removeEmptyDirectories(thumbnailRoot);
        }
        return new CleanupResult(deleted, bytes, kept);
    }

    private static String deletionReason(Path file, BasicFileAttributes attrs, Instant cutoff, Set<UUID> live) {
        // A .part is a generation that never completed; it can only be leftover from a crash.
        if (file.getFileName().toString().endsWith(".part")) {
            return "incomplete";
        }
        Optional<UUID> imageId = imageIdOf(file);
        if (imageId.isEmpty()) {
            return "unrecognized";
        }
        if (!live.contains(imageId.get())) {
            return "image gone";
        }
        if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
            return "idle";
        }
        return null;
    }

    /** The image id from a {@code {uuid}-{mtime}-{size}-w{width}.{ext}} file name. */
    private static Optional<UUID> imageIdOf(Path file) {
        String name = file.getFileName().toString();
        if (name.length() < 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(name.substring(0, 36)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void removeEmptyDirectories(Path root) throws IOException {
        List<Path> directories;
        try (Stream<Path> walk = Files.walk(root)) {
            directories = walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path directory : directories) {
            try (Stream<Path> entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) {
                    Files.delete(directory);
                }
            }
        }
    }
}
