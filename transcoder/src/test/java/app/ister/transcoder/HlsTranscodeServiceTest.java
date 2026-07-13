package app.ister.transcoder;

import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.FFmpegResultFuture;
import com.github.kokorin.jaffree.process.Stopper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.RETURNS_SELF;

@ExtendWith(MockitoExtension.class)
class HlsTranscodeServiceTest {

    @Mock private Jaffree jaffree;
    @Mock private FfprobeService ffprobeService;

    @TempDir Path tempDir;

    private HlsTranscodeService service;

    @BeforeEach
    void setUp() {
        service = new HlsTranscodeService(jaffree, ffprobeService);
        ReflectionTestUtils.setField(service, "tmpDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "segmentTimeoutMs", 5000L);
        ReflectionTestUtils.setField(service, "hwaccelProperty", "none");
        ReflectionTestUtils.setField(service, "hwaccelDevice", "/dev/dri/renderD128");
        ReflectionTestUtils.setField(service, "maxConcurrentFiles", 2);
        ReflectionTestUtils.setField(service, "maxConcurrentPasses", 4);
        ReflectionTestUtils.setField(service, "maxBackgroundFiles", 10);
        ReflectionTestUtils.setField(service, "maxBackgroundPasses", 4);
        ReflectionTestUtils.setField(service, "segmentStabilityMs", 200L);
        ReflectionTestUtils.setField(service, "passTimeoutMultiplier", 4.0);
        ReflectionTestUtils.setField(service, "passTimeoutMinSeconds", 1800L);
        ReflectionTestUtils.setField(service, "passStallTimeoutSeconds", 60L);
        ReflectionTestUtils.setField(service, "cacheRetentionHours", 2L);
        service.init();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Pass threads must not outlive the test: a lingering pass writes segments/done markers
        // into the @TempDir while JUnit deletes it, failing the cleanup of this or a later test.
        service.transcodeExecutor.shutdown();
        if (!service.transcodeExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            service.transcodeExecutor.shutdownNow();
            assertTrue(service.transcodeExecutor.awaitTermination(5, TimeUnit.SECONDS),
                    "transcode pass threads did not terminate");
        }
    }

    private static final Stopper NOOP_STOPPER = new Stopper() {
        @Override public void graceStop() { /* no-op: test double never runs a real process */ }
        @Override public void forceStop() { /* no-op: test double never runs a real process */ }
        @Override public void setProcess(Process process) { /* no-op: test double never runs a real process */ }
    };

    private static FFmpegResultFuture completedFFmpegFuture() {
        return new FFmpegResultFuture(CompletableFuture.completedFuture(null), NOOP_STOPPER);
    }

    private static FFmpegResultFuture neverCompletingFFmpegFuture() {
        return new FFmpegResultFuture(new CompletableFuture<>(), NOOP_STOPPER);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, CompletableFuture<Void>> activeGenerations() {
        return (ConcurrentHashMap<String, CompletableFuture<Void>>)
                ReflectionTestUtils.getField(service, "activeGenerations");
    }

    @SuppressWarnings("unchecked")
    private Set<String> filesWithAcquiredSlot() {
        return (Set<String>) ReflectionTestUtils.getField(service, "filesWithAcquiredSlot");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Object> generationLocks() {
        return (ConcurrentHashMap<String, Object>)
                ReflectionTestUtils.getField(service, "generationLocks");
    }

    // ========== init ==========

    @Test
    void initCreatesSemaphoreWithConfiguredPermits() {
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(service, "concurrentFileSlots");
        assertNotNull(slots);
        assertEquals(2, slots.availablePermits());
    }

    // ========== isPassActive ==========

    @Test
    void isPassActiveReturnsFalseWhenNoPassRegistered() {
        assertFalse(service.isPassActive("nonexistent_key"));
    }

    @Test
    void isPassActiveReturnsTrueWhenPassIsRunning() {
        String key = "test_video_720p";
        activeGenerations().put(key, new CompletableFuture<>()); // never completes

        assertTrue(service.isPassActive(key));
    }

    @Test
    void isPassActiveReturnsFalseWhenPassCompleted() {
        String key = "test_video_720p";
        activeGenerations().put(key, CompletableFuture.completedFuture(null));

        assertFalse(service.isPassActive(key));
    }

    // ========== getActiveFuture ==========

    @Test
    void getActiveFutureReturnsNullWhenNoPassRegistered() {
        assertNull(service.getActiveFuture("nonexistent"));
    }

    @Test
    void getActiveFutureReturnsRegisteredFuture() {
        String key = "test_key";
        CompletableFuture<Void> cf = new CompletableFuture<>();
        activeGenerations().put(key, cf);

        assertSame(cf, service.getActiveFuture(key));
    }

    // ========== hasCompletedPass ==========

    @Test
    void hasCompletedPassReturnsFalseWhenNoPass() {
        assertFalse(service.hasCompletedPass("nonexistent"));
    }

    @Test
    void hasCompletedPassReturnsTrueWhenNormallyCompleted() {
        String key = "test_video_720p";
        activeGenerations().put(key, CompletableFuture.completedFuture(null));

        assertTrue(service.hasCompletedPass(key));
    }

    @Test
    void hasCompletedPassReturnsFalseWhenPassFailed() {
        String key = "test_video_720p";
        activeGenerations().put(key, CompletableFuture.failedFuture(new RuntimeException("crash")));

        assertFalse(service.hasCompletedPass(key));
    }

    @Test
    void hasCompletedPassReturnsFalseWhenPassStillRunning() {
        String key = "test_video_720p";
        activeGenerations().put(key, new CompletableFuture<>());

        assertFalse(service.hasCompletedPass(key));
    }

    // ========== hasAnyActiveOrCompletedPassForFile ==========

    @Test
    void hasAnyActiveOrCompletedPassForFileReturnsFalseWhenNoPass() {
        assertFalse(service.hasAnyActiveOrCompletedPassForFile(UUID.randomUUID()));
    }

    @Test
    void hasAnyActiveOrCompletedPassForFileReturnsTrueWhenActivePass() {
        UUID id = UUID.randomUUID();
        activeGenerations().put(id + "_video_720p", new CompletableFuture<>());

        assertTrue(service.hasAnyActiveOrCompletedPassForFile(id));
    }

    @Test
    void hasAnyActiveOrCompletedPassForFileReturnsTrueWhenCompletedPass() {
        UUID id = UUID.randomUUID();
        activeGenerations().put(id + "_video_720p", CompletableFuture.completedFuture(null));

        assertTrue(service.hasAnyActiveOrCompletedPassForFile(id));
    }

    @Test
    void hasAnyActiveOrCompletedPassForFileReturnsFalseWhenOnlyFailedPass() {
        UUID id = UUID.randomUUID();
        activeGenerations().put(id + "_video_720p",
                CompletableFuture.failedFuture(new RuntimeException("err")));

        assertFalse(service.hasAnyActiveOrCompletedPassForFile(id));
    }

    // ========== getCachedKeyframes ==========

    @Test
    void getCachedKeyframesCallsFfprobeOnFirstCall() {
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0, 10.0));

        List<Double> result = service.getCachedKeyframes("/test/video.mkv");

        assertEquals(List.of(0.0, 5.0, 10.0), result);
        verify(ffprobeService, times(1)).getKeyframes("/test/video.mkv");
    }

    @Test
    void getCachedKeyframesReturnsCachedResultOnSubsequentCalls() {
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));

        service.getCachedKeyframes("/test/video.mkv");
        service.getCachedKeyframes("/test/video.mkv");

        verify(ffprobeService, times(1)).getKeyframes("/test/video.mkv");
    }

    // ========== getTotalDuration ==========

    @Test
    void getTotalDurationDelegatesToFfprobe() {
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(120.5);

        assertEquals(120.5, service.getTotalDuration("/test/video.mkv"));
    }

    // ========== stableSegmentOrNull ==========

    @Test
    void stableSegmentOrNullReturnsNullWhenFileDoesNotExist() throws IOException {
        Path nonExistent = tempDir.resolve("missing.ts");
        assertNull(service.stableSegmentOrNull(nonExistent));
    }

    @Test
    void stableSegmentOrNullReturnsNullWhenFileIsEmpty() throws IOException {
        Path empty = tempDir.resolve("empty.ts");
        Files.createFile(empty);
        assertNull(service.stableSegmentOrNull(empty));
    }

    @Test
    void stableSegmentOrNullReturnsPathWhenFileSizeIsStable() throws IOException {
        ReflectionTestUtils.setField(service, "segmentStabilityMs", 20L);
        Path seg = tempDir.resolve("stable.ts");
        Files.writeString(seg, "video segment data");

        // Non-blocking contract: the first call only records the size sample and returns null
        assertNull(service.stableSegmentOrNull(seg));

        // Same size after >= segmentStabilityMs -> stable. Poll instead of sleeping on a fixed delay.
        await().atMost(1, TimeUnit.SECONDS).until(() -> service.stableSegmentOrNull(seg) != null);

        Path result = service.stableSegmentOrNull(seg);
        assertNotNull(result);
        assertEquals(seg, result);

        // Memoized as known-stable: repeat calls return instantly
        assertEquals(seg, service.stableSegmentOrNull(seg));
    }

    // ========== waitForSegment ==========

    @Test
    void waitForSegmentTimesOutWhenFileNeverAppears() {
        ReflectionTestUtils.setField(service, "segmentTimeoutMs", 200L);
        Path missing = tempDir.resolve("never.ts");

        assertThrows(IOException.class, () -> service.waitForSegment(missing, "no_such_key"));
    }

    @Test
    void waitForSegmentReturnsSegmentWhenCompletedPassAndFilePresent() throws IOException {
        Path seg = tempDir.resolve("seg.ts");
        Files.writeString(seg, "data");
        String key = "done_key";
        activeGenerations().put(key, CompletableFuture.completedFuture(null));

        assertEquals(seg, service.waitForSegment(seg, key));
    }

    @Test
    void waitForSegmentThrowsWhenPassFailedExceptionally() {
        Path seg = tempDir.resolve("seg_failed.ts");
        String key = "failed_key";
        activeGenerations().put(key, CompletableFuture.failedFuture(new RuntimeException("crash")));

        assertThrows(IOException.class, () -> service.waitForSegment(seg, key));
    }

    @Test
    void waitForSegmentThrowsWhenPassCompletedButSegmentMissing() {
        Path seg = tempDir.resolve("missing_seg.ts");
        String key = "done_no_file";
        activeGenerations().put(key, CompletableFuture.completedFuture(null));

        assertThrows(IOException.class, () -> service.waitForSegment(seg, key));
    }

    // ========== cleanupOldFiles ==========

    @Test
    void cleanupOldFilesDoesNothingWhenRootDoesNotExist() {
        ReflectionTestUtils.setField(service, "tmpDir", tempDir.resolve("nonexistent").toString());
        assertDoesNotThrow(() -> service.cleanupOldFiles());
    }

    @Test
    void cleanupOldFilesRemovesStaleDirAndClearsGeneration() throws IOException {
        Path staleDir = tempDir.resolve("stale-dir-uuid");
        Files.createDirectories(staleDir);
        Path staleFile = staleDir.resolve("seg_video_720p_00000.ts");
        Files.writeString(staleFile, "old data");
        Files.setLastModifiedTime(staleFile, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        activeGenerations().put("stale-dir-uuid_video_720p", CompletableFuture.completedFuture(null));
        generationLocks().put("stale-dir-uuid_video_720p", new Object());

        service.cleanupOldFiles();

        assertFalse(Files.exists(staleDir));
        assertFalse(activeGenerations().containsKey("stale-dir-uuid_video_720p"));
        assertFalse(generationLocks().containsKey("stale-dir-uuid_video_720p"));
    }

    @Test
    void cleanupOldFilesKeepsDirWithRecentFile() throws IOException {
        Path recentDir = tempDir.resolve("recent-dir-uuid");
        Files.createDirectories(recentDir);
        Files.writeString(recentDir.resolve("seg_video_720p_00000.ts"), "new data");

        service.cleanupOldFiles();

        assertTrue(Files.exists(recentDir));
    }

    @Test
    void cleanupOldFilesKeepsPlaylistOnlyDirForever() throws IOException {
        // Pre-generated playlists (e.g. scan-time music playlists) have no .ts segments;
        // they are kilobytes and keeping them is what makes first play instant.
        Path playlistDir = tempDir.resolve("playlist-only-uuid");
        Files.createDirectories(playlistDir);
        Path master = playlistDir.resolve("master_d1_t1_sWEBVTT.m3u8");
        Files.writeString(master, "#EXTM3U");
        Files.setLastModifiedTime(master, FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        service.cleanupOldFiles();

        assertTrue(Files.exists(playlistDir));
    }

    @Test
    void cleanupOldFilesRemovesStaleSubtitleOnlyDir() throws IOException {
        // A dir with extracted subtitles but no segments (direct play with subtitles)
        // is not a pre-generated playlist dir and must still age out.
        Path subDir = tempDir.resolve("subtitle-only-uuid");
        Files.createDirectories(subDir);
        for (String name : new String[]{"master_d1_t1_sWEBVTT.m3u8", "sub_abc.srt", "seg_sub_abc_00000.vtt"}) {
            Path file = subDir.resolve(name);
            Files.writeString(file, "old data");
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));
        }

        service.cleanupOldFiles();

        assertFalse(Files.exists(subDir));
    }

    @Test
    void cleanupOldFilesKeepsDirWithMixOfOldAndRecentFiles() throws IOException {
        Path dir = tempDir.resolve("mixed-dir-uuid");
        Files.createDirectories(dir);
        Path oldFile = dir.resolve("seg_video_720p_00000.ts");
        Files.writeString(oldFile, "old data");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));
        Files.writeString(dir.resolve("seg_video_720p_00001.ts"), "new data");

        service.cleanupOldFiles();

        assertTrue(Files.exists(dir));
    }

    @Test
    void cleanupOldFilesSkipsDirWithUnexpiredKeepUntil() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        Path keepDir = tempDir.resolve(mediaFileId.toString());
        Files.createDirectories(keepDir);
        Path staleFile = keepDir.resolve("seg.ts");
        Files.writeString(staleFile, "data");
        Files.setLastModifiedTime(staleFile, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        service.extendKeepUntil(mediaFileId, System.currentTimeMillis() + 60_000);
        // Retention deadline is set, but the segment itself is stale
        Files.setLastModifiedTime(staleFile, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        service.cleanupOldFiles();

        assertTrue(Files.exists(keepDir));
    }

    @Test
    void cleanupOldFilesRemovesDirWithExpiredKeepUntil() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        Path keepDir = tempDir.resolve(mediaFileId.toString());
        Files.createDirectories(keepDir);
        service.extendKeepUntil(mediaFileId, System.currentTimeMillis() - 1_000);
        Path staleFile = keepDir.resolve("seg.ts");
        Files.writeString(staleFile, "data");
        try (var files = Files.list(keepDir)) {
            files.forEach(p -> {
                try {
                    Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        service.cleanupOldFiles();

        assertFalse(Files.exists(keepDir));
    }

    @Test
    void extendKeepUntilKeepsHighestDeadline() {
        UUID mediaFileId = UUID.randomUUID();
        long high = System.currentTimeMillis() + 3_600_000;
        service.extendKeepUntil(mediaFileId, high);
        service.extendKeepUntil(mediaFileId, System.currentTimeMillis() + 60_000);

        Path keepUntilFile = tempDir.resolve(mediaFileId.toString()).resolve("keep_until");
        assertTrue(Files.exists(keepUntilFile));
        assertDoesNotThrow(() -> assertEquals(high, Long.parseLong(Files.readString(keepUntilFile).trim())));
    }

    @Test
    void cleanupOldFilesDeletesLegacyKeepFiles() throws IOException {
        Path legacy = tempDir.resolve("pretranscode_keep_abc.txt");
        Files.writeString(legacy, "some-id\n");

        service.cleanupOldFiles();

        assertFalse(Files.exists(legacy));
    }

    @Test
    void cleanupOldFilesReleasesStaleTranscodeSlot() throws IOException {
        String dirName = "slot-holder-uuid";
        Path staleDir = tempDir.resolve(dirName);
        Files.createDirectories(staleDir);
        Path staleFile = staleDir.resolve("seg.ts");
        Files.writeString(staleFile, "data");
        Files.setLastModifiedTime(staleFile, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        filesWithAcquiredSlot().add(dirName);
        Semaphore semaphore = new Semaphore(0);
        ReflectionTestUtils.setField(service, "concurrentFileSlots", semaphore);

        service.cleanupOldFiles();

        assertEquals(1, semaphore.availablePermits());
        assertFalse(filesWithAcquiredSlot().contains(dirName));
    }

    @Test
    void cleanupOldFilesIgnoresKeepFileWithBlankLines() throws IOException {
        String keepId = "keep-blank-uuid";
        Path keepDir = tempDir.resolve(keepId);
        Files.createDirectories(keepDir);
        Path staleFile = keepDir.resolve("seg.ts");
        Files.writeString(staleFile, "data");
        Files.setLastModifiedTime(staleFile, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        // Keep file that contains blank lines and another ID — NOT keepId
        Path keepFile = tempDir.resolve("pretranscode_keep_xyz.txt");
        Files.writeString(keepFile, "\n  \nother-id\n");

        service.cleanupOldFiles();

        // Dir is not in keep list → should be removed
        assertFalse(Files.exists(keepDir));
    }

    // ========== ensurePassStarted ==========

    @Test
    void ensurePassStartedIsNoOpWhenPassAlreadyRunning() {
        String key = "file_video_720p";
        CompletableFuture<Void> running = new CompletableFuture<>(); // never completes
        activeGenerations().put(key, running);

        service.ensurePassStarted(key, () -> { throw new AssertionError("Should not be called"); });

        assertSame(running, activeGenerations().get(key));
    }

    // ========== background niceness ==========

    @Test
    void createNiceWrapperReturnsNullWhenDisabled() {
        ReflectionTestUtils.setField(service, "backgroundNice", 0);
        assertNull(service.createNiceWrapper());
    }

    @Test
    void createNiceWrapperWritesExecutableScript() throws IOException {
        ReflectionTestUtils.setField(service, "backgroundNice", 10);
        ReflectionTestUtils.setField(service, "nicePath", "/usr/bin/nice");
        ReflectionTestUtils.setField(service, "ffmpegDir", "/usr/bin");

        Path dir = service.createNiceWrapper();

        assertNotNull(dir, "nice is available on Linux, wrapper should be created");
        Path script = dir.resolve("ffmpeg");
        assertTrue(Files.isExecutable(script));
        String content = Files.readString(script);
        assertTrue(content.contains("nice -n 10"));
        assertTrue(content.contains("/usr/bin/ffmpeg"));
    }

    @Test
    void backgroundPassUsesNiceWrapperWhenAvailable() throws IOException {
        Path wrapperDir = Files.createDirectories(tempDir.resolve("nice-wrapper"));
        ReflectionTestUtils.setField(service, "niceWrapperDir", wrapperDir);

        assertNotNull(service.ffmpegFor(true));
        verify(jaffree, never()).getFFMPEG();

        service.ffmpegFor(false);
        verify(jaffree).getFFMPEG();
    }

    @Test
    void backgroundPassFallsBackToNormalFfmpegWithoutWrapper() {
        ReflectionTestUtils.setField(service, "niceWrapperDir", null);

        service.ffmpegFor(true);

        verify(jaffree).getFFMPEG();
    }

    // ========== background priority & preemption ==========

    @Test
    void backgroundPassIsDroppedWhenNoBackgroundFileSlotAvailable() {
        ReflectionTestUtils.setField(service, "backgroundFileBudget", new Semaphore(0));
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);

        service.ensurePassStarted("bg-file_video_720p", () -> started.set(true), true);

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !activeGenerations().containsKey("bg-file_video_720p"));
        assertFalse(started.get(), "dropped background pass must not start FFmpeg");
    }

    @Test
    void backgroundPassIsDroppedWhenPassBudgetExhausted() {
        ReflectionTestUtils.setField(service, "backgroundPassBudget", new Semaphore(0));
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);

        service.ensurePassStarted("bg-file_video_720p", () -> started.set(true), true);

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !activeGenerations().containsKey("bg-file_video_720p"));
        assertFalse(started.get(), "dropped background pass must not start FFmpeg");
    }

    @Test
    void interactivePassPreemptsRunningBackgroundPassForFileSlot() throws Exception {
        ReflectionTestUtils.setField(service, "concurrentFileSlots", new Semaphore(1));
        CountDownLatch backgroundRunning = new CountDownLatch(1);
        CountDownLatch interactiveRan = new CountDownLatch(1);

        service.ensurePassStarted("bg-file_video_720p", () -> {
            backgroundRunning.countDown();
            try {
                // Simulates a long FFmpeg pass; the await is interrupted by preemption
                new CountDownLatch(1).await(30, TimeUnit.SECONDS);
                throw new IllegalStateException("pass was not preempted");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }, true);
        assertTrue(backgroundRunning.await(2, TimeUnit.SECONDS));

        service.ensurePassStarted("fg-file_video_copy", interactiveRan::countDown, false);

        assertTrue(interactiveRan.await(5, TimeUnit.SECONDS), "interactive pass should run after preempting background");
        // The preempted background pass is forgotten so a later event can restart it
        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !activeGenerations().containsKey("bg-file_video_720p"));
    }

    @Test
    void backgroundRequestDoesNotRestartFailedPass() {
        String key = "bg-file_video_720p";
        activeGenerations().put(key, CompletableFuture.failedFuture(new IllegalStateException("boom")));

        service.ensurePassStarted(key, () -> { throw new AssertionError("Should not restart"); }, true);

        assertTrue(activeGenerations().get(key).isCompletedExceptionally());
    }

    // ========== startAudioPass ==========

    @Test
    void startAudioPassUsesSegmentTimeWhenNoKeyframes() {
        when(ffprobeService.getKeyframes("/test/audio.mkv")).thenReturn(List.of());
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        // Non-AAC source (e.g. Opus) must be re-encoded to AAC
        service.startAudioPass("/test/audio.mkv", tempDir, 0, AudioQuality.Q192K, "opus");

        verify(ffmpegMock).executeAsync();
    }

    @Test
    void startAudioPassCopiesWhenSourceIsAac() {
        when(ffprobeService.getKeyframes("/test/audio.m4a")).thenReturn(List.of());
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        // AAC source targeting the AAC 192k quality → stream copy instead of a re-encode,
        // so the AAC encoder (which can hang on some inputs) is never invoked.
        service.startAudioPass("/test/audio.m4a", tempDir, 0, AudioQuality.Q192K, "aac");

        verify(ffmpegMock).executeAsync();
    }

    @Test
    void startAudioPassForceStopsStalledPassThatProducesNoOutput() {
        ReflectionTestUtils.setField(service, "passStallTimeoutSeconds", 0L);
        when(ffprobeService.getKeyframes("/test/audio.mkv")).thenReturn(List.of());
        when(ffprobeService.getTotalDuration("/test/audio.mkv")).thenReturn(30.0);

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        // A pass that never completes and never writes a segment → must be reaped as stalled.
        FFmpegResultFuture neverCompletes = new FFmpegResultFuture(new CompletableFuture<>(), new Stopper() {
            @Override public void graceStop() { /* no-op */ }
            @Override public void forceStop() { /* no-op */ }
            @Override public void setProcess(Process process) { /* no-op */ }
        });
        when(ffmpegMock.executeAsync()).thenReturn(neverCompletes);

        assertThrows(IllegalStateException.class,
                () -> service.startAudioPass("/test/audio.mkv", tempDir, 0, AudioQuality.Q192K, "opus"));
    }

    // ========== generateVideoSegment ==========

    @Test
    void generateVideoSegmentCopyModeCallsExecute() {
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        service.generateVideoSegment("/test/video.mkv", tempDir.resolve("out.ts"), 0.0, 10.0, VideoQuality.COPY);

        verify(ffmpegMock).executeAsync();
    }

    @Test
    void generateVideoSegmentTranscodeModeCallsExecute() {
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        service.generateVideoSegment("/test/video.mkv", tempDir.resolve("out.ts"), 0.0, 10.0, VideoQuality.Q720P);

        verify(ffmpegMock).executeAsync();
    }

    // ========== generateAudioSegment ==========

    @Test
    void generateAudioSegmentTranscodeModeCallsExecute() {
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        service.generateAudioSegment("/test/audio.mkv", tempDir.resolve("out.ts"), 0.0, 10.0, 0, AudioQuality.Q192K, "aac");

        verify(ffmpegMock).executeAsync();
    }

    @Test
    void generateAudioSegmentCopyModeNativeCodecCallsExecute() {
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        // AAC and MP3 are MPEG-TS native — stream copy should be used
        service.generateAudioSegment("/test/audio.mp3", tempDir.resolve("out.ts"), 0.0, 10.0, 0, AudioQuality.COPY, "mp3");

        verify(ffmpegMock).executeAsync();
    }

    @Test
    void generateAudioSegmentCopyModeNonNativeCodecTranscodesToAac() {
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        // FLAC cannot be muxed into MPEG-TS — must be transcoded to AAC
        service.generateAudioSegment("/test/audio.flac", tempDir.resolve("out.ts"), 0.0, 10.0, 0, AudioQuality.COPY, "flac");

        verify(ffmpegMock).executeAsync();
    }

    @Test
    void generateVideoSegmentThrowsWhenFfmpegFails() {
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync())
                .thenReturn(new FFmpegResultFuture(CompletableFuture.failedFuture(new IllegalStateException("boom")), NOOP_STOPPER));

        Path out = tempDir.resolve("out.ts");
        assertThrows(IllegalStateException.class,
                () -> service.generateVideoSegment("/test/video.mkv", out, 0.0, 10.0, VideoQuality.COPY));
    }

    // ========== pass timeouts ==========

    @Test
    void startVideoPassForceStopsPassThatExceedsTheOverallTimeout() {
        ReflectionTestUtils.setField(service, "passTimeoutMultiplier", 0.0);
        ReflectionTestUtils.setField(service, "passTimeoutMinSeconds", 0L);
        ReflectionTestUtils.setField(service, "passStallTimeoutSeconds", 3600L);
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(neverCompletingFFmpegFuture());

        assertThrows(IllegalStateException.class,
                () -> service.startVideoPass("/test/video.mkv", tempDir, VideoQuality.Q720P));
    }

    @Test
    void startVideoPassKeepsRunningWhileItProducesSegments() throws IOException {
        // Stall deadline has passed, but a segment is on disk → the pass is healthy and runs on
        // until the FFmpeg future completes.
        ReflectionTestUtils.setField(service, "passStallTimeoutSeconds", 0L);
        Path cacheDir = Files.createDirectories(tempDir.resolve("producing"));
        Files.writeString(cacheDir.resolve("seg_video_720p_00000.ts"), "data");
        when(ffprobeService.getKeyframes("/test/video.mkv")).thenReturn(List.of(0.0, 5.0));
        when(ffprobeService.getTotalDuration("/test/video.mkv")).thenReturn(10.0);

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        service.startVideoPass("/test/video.mkv", cacheDir, VideoQuality.Q720P);

        assertTrue(service.hasDoneMarker(cacheDir, "seg_video_720p_"));
    }

    // ========== done markers ==========

    @Test
    void hasDoneMarkerIsFalseBeforeAPassCompleted() {
        assertFalse(service.hasDoneMarker(tempDir, "seg_video_720p_"));
    }

    @Test
    void startAudioPassWritesDoneMarker() {
        when(ffprobeService.getKeyframes("/test/audio.mkv")).thenReturn(List.of());
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        service.startAudioPass("/test/audio.mkv", tempDir, 1, AudioQuality.Q192K, "opus");

        assertTrue(service.hasDoneMarker(tempDir, "seg_audio_1_192k_"));
    }

    // ========== cache dir failures ==========

    @Test
    void startVideoPassThrowsWhenCacheDirCannotBeCreated() throws IOException {
        Path regularFile = tempDir.resolve("not-a-dir");
        Files.writeString(regularFile, "x");
        Path impossibleCacheDir = regularFile.resolve("cache");

        assertThrows(IllegalStateException.class,
                () -> service.startVideoPass("/test/video.mkv", impossibleCacheDir, VideoQuality.Q720P));
    }

    @Test
    void startAudioPassThrowsWhenCacheDirCannotBeCreated() throws IOException {
        Path regularFile = tempDir.resolve("not-a-dir-audio");
        Files.writeString(regularFile, "x");
        Path impossibleCacheDir = regularFile.resolve("cache");

        assertThrows(IllegalStateException.class,
                () -> service.startAudioPass("/test/audio.mkv", impossibleCacheDir, 0, AudioQuality.Q192K, "opus"));
    }

    // ========== hasActivePassForFile ==========

    @Test
    void hasActivePassForFileIsTrueWhileAPassIsEncoding() {
        UUID id = UUID.randomUUID();
        activeGenerations().put(id + "_video_720p", new CompletableFuture<>());

        assertTrue(service.hasActivePassForFile(id));
    }

    @Test
    void hasActivePassForFileIsFalseWhenAllPassesFinished() {
        UUID id = UUID.randomUUID();
        activeGenerations().put(id + "_video_720p", CompletableFuture.completedFuture(null));

        assertFalse(service.hasActivePassForFile(id));
        assertFalse(service.hasActivePassForFile(UUID.randomUUID()));
    }

    // ========== probe cache seeding ==========

    @Test
    void seedAudioOnlyKeyframesSkipsTheKeyframeProbe() {
        service.seedAudioOnlyKeyframes("/test/audio.mp3");

        assertEquals(List.of(), service.getCachedKeyframes("/test/audio.mp3"));
        verify(ffprobeService, never()).getKeyframes("/test/audio.mp3");
    }

    @Test
    void seedDurationSkipsTheDurationProbe() {
        service.seedDuration("/test/audio.mp3", 42.0);

        assertEquals(42.0, service.getTotalDuration("/test/audio.mp3"));
        verify(ffprobeService, never()).getTotalDuration("/test/audio.mp3");
    }

    // ========== stableSegmentOrNull / waitForSegment ==========

    @Test
    void stableSegmentOrNullForgetsAKnownSegmentThatWasCleanedUp() throws IOException {
        ReflectionTestUtils.setField(service, "segmentStabilityMs", 0L);
        Path seg = tempDir.resolve("gone.ts");
        Files.writeString(seg, "data");

        assertNull(service.stableSegmentOrNull(seg)); // first call records the size sample
        assertEquals(seg, service.stableSegmentOrNull(seg)); // now memoized as stable

        Files.delete(seg);

        assertNull(service.stableSegmentOrNull(seg));
    }

    @Test
    void waitForSegmentReturnsSegmentThatBecomesStableWhilePassIsRunning() throws IOException {
        ReflectionTestUtils.setField(service, "segmentStabilityMs", 0L);
        ReflectionTestUtils.setField(service, "segmentTimeoutMs", 5000L);
        Path seg = tempDir.resolve("running.ts");
        Files.writeString(seg, "data");
        String key = "running_video_720p";
        activeGenerations().put(key, new CompletableFuture<>()); // pass still encoding

        assertEquals(seg, service.waitForSegment(seg, key));
    }

    // ========== pass admission failures ==========

    @Test
    void passFailsWhenTheExecutorIsAlreadyShutDown() {
        service.transcodeExecutor.shutdownNow();
        String key = "shutdown-file_video_720p";

        service.ensurePassStarted(key, () -> { /* never runs: the pool is gone */ }, true);

        assertTrue(activeGenerations().get(key).isCompletedExceptionally());
    }

    @Test
    void backgroundPassIsDroppedWhenAllFileSlotsAreTaken() {
        ReflectionTestUtils.setField(service, "concurrentFileSlots", new Semaphore(0));
        Semaphore backgroundBudget = new Semaphore(1);
        ReflectionTestUtils.setField(service, "backgroundFileBudget", backgroundBudget);

        service.ensurePassStarted("bg-file_video_720p", () -> {
            throw new AssertionError("dropped background pass must not start FFmpeg");
        }, true);

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !activeGenerations().containsKey("bg-file_video_720p"));
        // The background budget is handed back when the file slot could not be acquired.
        assertEquals(1, backgroundBudget.availablePermits());
    }

    @Test
    void startAudioPassAlignsKeyframesWhenReencoding() {
        when(ffprobeService.getKeyframes("/test/audio.mkv")).thenReturn(List.of(0.0, 5.0, 10.0));
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        // Non-AAC source with keyframes → the AAC encoder is forced onto the segment cut points.
        service.startAudioPass("/test/audio.mkv", tempDir, 0, AudioQuality.Q64K, "opus");

        verify(ffmpegMock).executeAsync();
        assertTrue(service.hasDoneMarker(tempDir, "seg_audio_0_64k_"));
    }

    @Test
    void extendKeepUntilSurvivesAnUnwritableCacheRoot() throws IOException {
        Path regularFile = tempDir.resolve("tmp-root-is-a-file");
        Files.writeString(regularFile, "x");
        ReflectionTestUtils.setField(service, "tmpDir", regularFile.toString());

        assertDoesNotThrow(() -> service.extendKeepUntil(UUID.randomUUID(), System.currentTimeMillis() + 60_000));
    }

    // ========== nice wrapper failures ==========

    @Test
    void createNiceWrapperReturnsNullWhenNiceProbeFails() {
        ReflectionTestUtils.setField(service, "backgroundNice", 10);
        ReflectionTestUtils.setField(service, "nicePath", "/bin/false");

        assertNull(service.createNiceWrapper());
    }

    @Test
    void createNiceWrapperReturnsNullWhenNiceBinaryIsMissing() {
        ReflectionTestUtils.setField(service, "backgroundNice", 10);
        ReflectionTestUtils.setField(service, "nicePath", tempDir.resolve("no-such-nice").toString());

        assertNull(service.createNiceWrapper());
    }

    // ========== cleanup edge cases ==========

    @Test
    void cleanupOldFilesSkipsTheNiceWrapperDirectory() throws IOException {
        Path wrapperDir = Files.createDirectories(tempDir.resolve("ffmpeg-nice"));
        Path script = wrapperDir.resolve("ffmpeg");
        Files.writeString(script, "#!/bin/sh\n");
        Files.setLastModifiedTime(script, FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        service.cleanupOldFiles();

        assertTrue(Files.exists(script), "the nice wrapper must survive the cache cleanup");
    }

    @Test
    void cleanupOldFilesRemovesDirWithUnreadableKeepUntilFile() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("bad-keep-until-uuid"));
        Files.writeString(dir.resolve("keep_until"), "not-a-number");
        Files.writeString(dir.resolve("seg.ts"), "data");
        try (var files = Files.list(dir)) {
            files.forEach(p -> {
                try {
                    Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        service.cleanupOldFiles();

        assertFalse(Files.exists(dir));
    }
}
