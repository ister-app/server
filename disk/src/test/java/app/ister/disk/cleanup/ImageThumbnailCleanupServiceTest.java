package app.ister.disk.cleanup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ImageThumbnailCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final Duration MAX_IDLE = Duration.ofDays(30);

    @TempDir Path tempDir;

    private ImageThumbnailCleanupService service;

    @BeforeEach
    void setUp() {
        service = new ImageThumbnailCleanupService(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Path thumbnail(UUID id, String suffix, Duration idleFor) throws IOException {
        Path dir = tempDir.resolve(id.toString().substring(0, 2));
        Files.createDirectories(dir);
        Path file = dir.resolve("%s-1a2b3c-4096-w320.%s".formatted(id, suffix));
        Files.writeString(file, "thumbnail bytes");
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(idleFor)));
        return file;
    }

    private ImageThumbnailCleanupService.CleanupResult clean(Set<UUID> live, boolean dryRun) throws IOException {
        return service.clean(tempDir, MAX_IDLE, candidates -> {
            Set<UUID> alive = new java.util.HashSet<>(candidates);
            alive.retainAll(live);
            return alive;
        }, dryRun);
    }

    @Test
    void keepsAThumbnailThatWasRequestedRecently() throws IOException {
        UUID id = UUID.randomUUID();
        Path file = thumbnail(id, "jpg", Duration.ofDays(3));

        var result = clean(Set.of(id), false);

        assertEquals(0, result.filesDeleted());
        assertEquals(1, result.filesKept());
        assertTrue(Files.exists(file));
    }

    @Test
    void removesAThumbnailNobodyHasAskedForInTheIdleWindow() throws IOException {
        UUID id = UUID.randomUUID();
        Path file = thumbnail(id, "jpg", Duration.ofDays(31));

        var result = clean(Set.of(id), false);

        assertEquals(1, result.filesDeleted());
        assertFalse(Files.exists(file));
        // The now-empty shard goes with it, so the tree does not accumulate directories.
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void removesAThumbnailWhoseImageIsGoneEvenWhenItIsHot() throws IOException {
        Path file = thumbnail(UUID.randomUUID(), "png", Duration.ofMinutes(1));

        var result = clean(Set.of(), false);

        assertEquals(1, result.filesDeleted());
        assertFalse(Files.exists(file));
    }

    @Test
    void removesLeftoverPartFilesAndUnrecognisedNames() throws IOException {
        Files.createDirectories(tempDir.resolve("ab"));
        Path part = tempDir.resolve("ab").resolve("%s-1a-2-w320.jpg.part".formatted(UUID.randomUUID()));
        Files.writeString(part, "half written");
        Files.setLastModifiedTime(part, FileTime.from(NOW));
        Path stray = tempDir.resolve("ab").resolve("notathumbnail.txt");
        Files.writeString(stray, "who put this here");

        var result = clean(Set.of(), false);

        assertEquals(2, result.filesDeleted());
        assertFalse(Files.exists(part));
        assertFalse(Files.exists(stray));
    }

    @Test
    void dryRunDeletesNothing() throws IOException {
        UUID id = UUID.randomUUID();
        Path file = thumbnail(id, "jpg", Duration.ofDays(90));

        var result = clean(Set.of(id), true);

        assertEquals(1, result.filesDeleted());
        assertTrue(Files.exists(file));
    }

    @Test
    void anAbsentRootIsNotAnError() throws IOException {
        var result = service.clean(tempDir.resolve("never-created"), MAX_IDLE, candidates -> candidates, false);

        assertEquals(0, result.filesDeleted());
        assertEquals(0, result.filesKept());
    }
}
