package app.ister.transcoder.cleanup;

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
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TmpCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-11T04:30:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TmpCleanupService service = new TmpCleanupService();

    private FileSystem fs;
    private Path tmp;

    private final UUID existingIdle = UUID.randomUUID();
    private final UUID existingActive = UUID.randomUUID();
    private final UUID existingFresh = UUID.randomUUID();
    private final UUID orphan = UUID.randomUUID();

    private final Predicate<UUID> mediaFileExists = id -> !id.equals(orphan);
    private final Predicate<UUID> hasActivePass = id -> id.equals(existingActive);

    @BeforeEach
    void setUp() throws IOException {
        fs = Jimfs.newFileSystem(Configuration.unix());
        tmp = fs.getPath("/tmp/ister");
        Files.createDirectories(tmp);
    }

    @AfterEach
    void tearDown() throws IOException {
        fs.close();
    }

    private Path dir(String name, Instant mtime) throws IOException {
        Path d = tmp.resolve(name);
        Files.createDirectories(d);
        Path seg = d.resolve("seg_0.ts");
        Files.write(seg, new byte[]{1, 2, 3, 4});
        Files.setLastModifiedTime(seg, FileTime.from(mtime));
        Files.setLastModifiedTime(d, FileTime.from(mtime));
        return d;
    }

    @Test
    void deletesOrphanAndIdleButKeepsActiveFreshAndForeign() throws IOException {
        Path idle = dir(existingIdle.toString(), NOW.minus(Duration.ofDays(2)));
        Path active = dir(existingActive.toString(), NOW.minus(Duration.ofDays(2)));
        Path fresh = dir(existingFresh.toString(), NOW.minus(Duration.ofHours(1)));
        Path orphanDir = dir(orphan.toString(), NOW.minus(Duration.ofHours(1)));
        Path foreign = dir("not-a-uuid", NOW.minus(Duration.ofDays(30)));

        TmpCleanupService.CleanupResult res = service.clean(
                tmp, mediaFileExists, hasActivePass, clock, Duration.ofHours(24), false);

        assertFalse(Files.exists(idle), "idle dir with no active pass must be deleted");
        assertFalse(Files.exists(orphanDir), "orphan dir (media file gone) must be deleted even when fresh");
        assertTrue(Files.exists(active), "dir with active pass must stay");
        assertTrue(Files.exists(fresh), "recently active dir must stay (grace)");
        assertTrue(Files.exists(foreign), "non-uuid dir must not be touched");
        assertEquals(2, res.dirsDeleted());
        assertEquals(3, res.dirsKept());
    }

    @Test
    void dryRunDeletesNothing() throws IOException {
        Path idle = dir(existingIdle.toString(), NOW.minus(Duration.ofDays(2)));

        TmpCleanupService.CleanupResult res = service.clean(
                tmp, mediaFileExists, hasActivePass, clock, Duration.ofHours(24), true);

        assertTrue(Files.exists(idle), "dry-run must not delete");
        assertEquals(1, res.dirsDeleted());
    }
}
