package app.ister.disk.cleanup;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-11T04:30:00Z");
    private final CacheCleanupService service = new CacheCleanupService(Clock.fixed(NOW, ZoneOffset.UTC));

    private FileSystem fs;
    private Path cache;

    @BeforeEach
    void setUp() throws IOException {
        fs = Jimfs.newFileSystem(Configuration.unix());
        cache = fs.getPath("/cache");
        Files.createDirectories(cache);
    }

    @AfterEach
    void tearDown() throws IOException {
        fs.close();
    }

    private Path file(String rel, Instant mtime) throws IOException {
        Path p = cache.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(p, FileTime.from(mtime));
        return p;
    }

    @Test
    void deletesOldZombieButKeepsReferencedAndFreshFiles() throws IOException {
        Path referenced = file("referenced.jpg", NOW.minus(Duration.ofDays(2)));
        Path zombieOld = file("zombie-old.jpg", NOW.minus(Duration.ofDays(2)));
        Path zombieFresh = file("zombie-fresh.jpg", NOW.minus(Duration.ofHours(1)));
        Path albumCover = file("album-covers/abc/cover.jpg", NOW.minus(Duration.ofDays(5)));

        Set<String> refs = Set.of(referenced.toString(), albumCover.toString());

        CacheCleanupService.CleanupResult res =
                service.clean(cache, refs, Duration.ofHours(24), false);

        assertTrue(Files.exists(referenced), "referenced file must stay");
        assertTrue(Files.exists(albumCover), "referenced album cover must stay");
        assertTrue(Files.exists(zombieFresh), "fresh unreferenced file must stay (grace)");
        assertFalse(Files.exists(zombieOld), "old unreferenced file must be deleted");
        assertEquals(1, res.filesDeleted());
        assertEquals(3, res.filesKept());
    }

    @Test
    void dryRunDeletesNothing() throws IOException {
        Path zombieOld = file("zombie-old.jpg", NOW.minus(Duration.ofDays(2)));

        CacheCleanupService.CleanupResult res =
                service.clean(cache, Set.of(), Duration.ofHours(24), true);

        assertTrue(Files.exists(zombieOld), "dry-run must not delete");
        assertEquals(1, res.filesDeleted(), "dry-run still counts what it would delete");
    }

    @Test
    void missingRootIsNoop() throws IOException {
        CacheCleanupService.CleanupResult res =
                service.clean(fs.getPath("/does-not-exist"), Set.of(), Duration.ofHours(24), false);
        assertEquals(0, res.filesDeleted());
    }
}
