package app.ister.core.storage;

import app.ister.core.config.S3Properties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.StorageKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalCopyTest {

    private static final String URI = "s3://bucket/library/Author/Book.epub";
    private static final String KEY = "library/Author/Book.epub";

    @TempDir
    Path tmp;

    @Mock
    private ObjectStoreRegistry registry;

    @Mock
    private ObjectStore store;

    private Path scratchDir;
    private DirectoryEntity s3Directory;
    private LocalCopy subject;

    @BeforeEach
    void setUp() {
        scratchDir = tmp.resolve("scratch");
        S3Properties properties = new S3Properties();
        properties.setLocalCopyDir(scratchDir.toString());
        properties.setLocalCopyMaxBytes(100);
        subject = new LocalCopy(registry, properties, tmp.resolve("unused-tmp").toString());

        s3Directory = DirectoryEntity.builder()
                .name("s3-books")
                .storageKind(StorageKind.S3)
                .s3Connection("main")
                .path("s3://bucket/library")
                .build();
        s3Directory.setId(UUID.randomUUID());
    }

    private void givenStoreWrites(String content) throws IOException {
        when(registry.forDirectory(s3Directory)).thenReturn(store);
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return null;
        }).when(store).copyToLocal(eq(KEY), any(Path.class));
    }

    private Path expectedTarget(String uri) {
        return scratchDir.resolve(LocalCopy.sha256(uri)).resolve(ObjectRef.parse(uri).fileName());
    }

    @Test
    void localCopyDirFallsBackToTmpDirWhenUnset() throws IOException {
        S3Properties properties = new S3Properties();
        LocalCopy fallback = new LocalCopy(registry, properties, tmp.toString());
        when(registry.forDirectory(s3Directory)).thenReturn(store);
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "x");
            return null;
        }).when(store).copyToLocal(eq(KEY), any(Path.class));

        try (LocalCopy.Handle handle = fallback.of(s3Directory, URI)) {
            assertTrue(handle.path().startsWith(tmp.resolve("s3-scratch")));
        }
    }

    @Test
    void localPathIsHandedBackUntouched() throws IOException {
        DirectoryEntity local = DirectoryEntity.builder().name("local").path("/media").build();

        try (LocalCopy.Handle handle = subject.of(local, "/media/Movies/film.mkv")) {
            assertEquals(Path.of("/media/Movies/film.mkv"), handle.path());
        }
        verifyNoInteractions(registry);
    }

    @Test
    void ofEntityUsesTheEntityDirectoryAndPath() throws IOException {
        MediaFileEntity entity = MediaFileEntity.builder().path("/media/Movies/film.mkv").build();
        entity.setDirectoryEntity(DirectoryEntity.builder().name("local").path("/media").build());

        try (LocalCopy.Handle handle = subject.of(entity)) {
            assertEquals(Path.of("/media/Movies/film.mkv"), handle.path());
        }
    }

    @Test
    void cacheMissDownloadsIntoHashedScratchDir() throws IOException {
        givenStoreWrites("epub bytes");

        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            assertEquals(expectedTarget(URI), handle.path());
            assertEquals("epub bytes", Files.readString(handle.path()));
        }
        verify(store).copyToLocal(eq(KEY), eq(expectedTarget(URI)));
    }

    @Test
    void cacheHitDoesNotDownloadAgainAndTouchesTheFile() throws IOException {
        givenStoreWrites("epub bytes");
        Path target;
        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            target = handle.path();
        }
        Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2000-01-01T00:00:00Z")));

        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            assertEquals(target, handle.path());
        }

        verify(store, times(1)).copyToLocal(anyString(), any(Path.class));
        assertTrue(Files.getLastModifiedTime(target).toInstant().isAfter(Instant.parse("2001-01-01T00:00:00Z")));
    }

    @Test
    void closingTheHandleKeepsTheCopy() throws IOException {
        givenStoreWrites("epub bytes");
        Path target;
        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            target = handle.path();
        }
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    void differentObjectsGetDifferentScratchDirsEvenWithTheSameFileName() throws IOException {
        String other = "s3://bucket/library/Other/Book.epub";
        when(registry.forDirectory(s3Directory)).thenReturn(store);
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.writeString(target, invocation.getArgument(0, String.class));
            return null;
        }).when(store).copyToLocal(anyString(), any(Path.class));

        try (LocalCopy.Handle first = subject.of(s3Directory, URI);
             LocalCopy.Handle second = subject.of(s3Directory, other)) {
            assertNotEquals(first.path(), second.path());
            assertEquals("Book.epub", first.path().getFileName().toString());
            assertEquals("Book.epub", second.path().getFileName().toString());
            assertEquals(KEY, Files.readString(first.path()));
            assertEquals("library/Other/Book.epub", Files.readString(second.path()));
        }
    }

    @Test
    void storeFailureIsPropagatedAndReleasesThePin() throws IOException {
        when(registry.forDirectory(s3Directory)).thenReturn(store);
        doThrow(new NoSuchFileException(KEY)).when(store).copyToLocal(eq(KEY), any(Path.class));

        assertThrows(NoSuchFileException.class, () -> subject.of(s3Directory, URI));

        // the pin was released: evict is allowed to clean up the (absent) copy, i.e. nothing is in use
        assertFalse(Files.exists(expectedTarget(URI)));
        subject.evict(URI);
        assertFalse(Files.exists(expectedTarget(URI).getParent()));
    }

    @Test
    void runtimeFailureFromRegistryIsPropagated() {
        when(registry.forDirectory(s3Directory)).thenThrow(new IllegalStateException("no connection"));

        assertThrows(IllegalStateException.class, () -> subject.of(s3Directory, URI));
    }

    @Test
    void localPathOrNullReturnsNullWhenTheObjectDoesNotExist() throws IOException {
        when(registry.forDirectory(s3Directory)).thenReturn(store);
        doThrow(new NoSuchFileException(KEY)).when(store).copyToLocal(eq(KEY), any(Path.class));
        MediaFileEntity entity = MediaFileEntity.builder().path(URI).build();
        entity.setDirectoryEntity(s3Directory);

        assertNull(subject.localPathOrNull(entity));
    }

    @Test
    void localPathOrNullReturnsTheCachedCopy() throws IOException {
        givenStoreWrites("epub bytes");
        MediaFileEntity entity = MediaFileEntity.builder().path(URI).build();
        entity.setDirectoryEntity(s3Directory);

        Path path = subject.localPathOrNull(entity);

        assertEquals(expectedTarget(URI), path);
        assertTrue(Files.isRegularFile(path));
    }

    @Test
    void localPathOrNullForLocalFileIsThePathItself() throws IOException {
        MediaFileEntity entity = MediaFileEntity.builder().path("/media/Books/a.epub").build();
        entity.setDirectoryEntity(DirectoryEntity.builder().name("local").path("/media").build());

        assertEquals(Path.of("/media/Books/a.epub"), subject.localPathOrNull(entity));
    }

    @Test
    void concurrentReadersDownloadOnce() throws Exception {
        when(registry.forDirectory(s3Directory)).thenReturn(store);
        AtomicInteger downloads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            downloads.incrementAndGet();
            started.countDown();
            Thread.sleep(50);
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "epub bytes");
            return null;
        }).when(store).copyToLocal(eq(KEY), any(Path.class));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Path>> futures = List.of(
                    pool.submit(() -> readThrough()),
                    pool.submit(() -> {
                        started.await();
                        return readThrough();
                    }),
                    pool.submit(() -> {
                        started.await();
                        return readThrough();
                    }),
                    pool.submit(() -> {
                        started.await();
                        return readThrough();
                    }));
            for (Future<Path> future : futures) {
                assertEquals(expectedTarget(URI), future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, downloads.get());
        assertEquals("epub bytes", Files.readString(expectedTarget(URI)));
    }

    private Path readThrough() throws IOException {
        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            return handle.path();
        }
    }

    @Test
    void evictIgnoresLocalPaths() {
        subject.evict("/media/Books/a.epub");

        assertFalse(Files.exists(scratchDir));
    }

    @Test
    void evictDeletesTheCopyAndItsDirectory() throws IOException {
        givenStoreWrites("old bytes");
        Path target;
        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            target = handle.path();
        }

        subject.evict(URI);

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.getParent()));
    }

    @Test
    void evictLeavesAPinnedCopyAlone() throws IOException {
        givenStoreWrites("old bytes");

        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            subject.evict(URI);
            assertTrue(Files.isRegularFile(handle.path()));
        }
    }

    @Test
    void evictOfAnUnknownObjectIsANoOp() throws IOException {
        Files.createDirectories(scratchDir);

        subject.evict("s3://bucket/library/never/downloaded.epub");

        assertTrue(Files.isDirectory(scratchDir));
    }

    @Test
    void evictedCopyIsDownloadedAgainOnTheNextRead() throws IOException {
        givenStoreWrites("bytes");
        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            assertNotNull(handle.path());
        }
        subject.evict(URI);

        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            assertTrue(Files.isRegularFile(handle.path()));
        }
        verify(store, times(2)).copyToLocal(eq(KEY), any(Path.class));
    }

    @Test
    void sweepWithoutScratchDirIsANoOp() {
        subject.sweep();

        assertFalse(Files.exists(scratchDir));
    }

    @Test
    void sweepKeepsEverythingUnderTheCap() throws IOException {
        Path a = file("a", 40, Instant.parse("2020-01-01T00:00:00Z"));
        Path b = file("b", 40, Instant.parse("2020-01-02T00:00:00Z"));

        subject.sweep();

        assertTrue(Files.exists(a));
        assertTrue(Files.exists(b));
    }

    @Test
    void sweepDropsLeastRecentlyUsedFirstUntilUnderTheCap() throws IOException {
        Path oldest = file("oldest", 60, Instant.parse("2020-01-01T00:00:00Z"));
        Path middle = file("middle", 60, Instant.parse("2020-01-02T00:00:00Z"));
        Path newest = file("newest", 60, Instant.parse("2020-01-03T00:00:00Z"));

        subject.sweep();

        assertFalse(Files.exists(oldest));
        assertFalse(Files.exists(oldest.getParent()));
        assertFalse(Files.exists(middle));
        assertTrue(Files.exists(newest));
    }

    @Test
    void sweepSkipsPinnedCopiesAndPartialDownloads() throws IOException {
        givenStoreWrites("x".repeat(90));
        Path part = file("partial.epub.part", 90, Instant.parse("2020-01-01T00:00:00Z"));
        Path unpinned = file("unpinned", 90, Instant.parse("2020-01-02T00:00:00Z"));

        try (LocalCopy.Handle pinned = subject.of(s3Directory, URI)) {
            Files.setLastModifiedTime(pinned.path(), FileTime.from(Instant.parse("2019-01-01T00:00:00Z")));

            subject.sweep();

            assertTrue(Files.exists(pinned.path()), "a pinned copy is never swept");
            assertTrue(Files.exists(part), "an in-flight download is never swept");
            assertFalse(Files.exists(unpinned));
        }
    }

    @Test
    void sweepTakesAnUnpinnedCopyAfterItsHandleClosed() throws IOException {
        givenStoreWrites("x".repeat(90));
        Path target;
        try (LocalCopy.Handle handle = subject.of(s3Directory, URI)) {
            target = handle.path();
        }
        Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2019-01-01T00:00:00Z")));
        file("newer", 90, Instant.parse("2020-01-01T00:00:00Z"));

        subject.sweep();

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.getParent()));
    }

    @Test
    void sha256IsStableHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", LocalCopy.sha256(""));
        assertEquals(LocalCopy.sha256(URI), LocalCopy.sha256(URI));
        assertNotEquals(LocalCopy.sha256(URI), LocalCopy.sha256(URI + "x"));
    }

    private Path file(String name, int size, Instant lastModified) throws IOException {
        Path dir = scratchDir.resolve(LocalCopy.sha256(name));
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.write(file, new byte[size]);
        Files.setLastModifiedTime(file, FileTime.from(lastModified));
        return file;
    }
}
