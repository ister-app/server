package app.ister.transcoder;

import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.FFmpegResultFuture;
import com.github.kokorin.jaffree.process.Stopper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
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
import java.util.concurrent.Semaphore;

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
        ReflectionTestUtils.setField(service, "segmentStabilityMs", 200L);
        ReflectionTestUtils.setField(service, "passTimeoutMultiplier", 4.0);
        ReflectionTestUtils.setField(service, "passTimeoutMinSeconds", 1800L);
        ReflectionTestUtils.setField(service, "cacheRetentionHours", 2L);
        service.init();
    }

    private static FFmpegResultFuture completedFFmpegFuture() {
        return new FFmpegResultFuture(CompletableFuture.completedFuture(null), new Stopper() {
            @Override public void graceStop() {}
            @Override public void forceStop() {}
            @Override public void setProcess(Process process) {}
        });
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
    void stableSegmentOrNullReturnsPathWhenFileSizeIsStable() throws IOException, InterruptedException {
        ReflectionTestUtils.setField(service, "segmentStabilityMs", 20L);
        Path seg = tempDir.resolve("stable.ts");
        Files.writeString(seg, "video segment data");

        // Non-blocking contract: the first call only records the size sample and returns null
        assertNull(service.stableSegmentOrNull(seg));

        Thread.sleep(30);

        // Same size after >= segmentStabilityMs -> stable
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
    void cleanupOldFilesSkipsDirListedInKeepFile() throws IOException {
        String keepId = "keep-this-uuid";
        Path keepDir = tempDir.resolve(keepId);
        Files.createDirectories(keepDir);
        Path staleFile = keepDir.resolve("seg.ts");
        Files.writeString(staleFile, "data");
        Files.setLastModifiedTime(staleFile, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        Path keepFile = tempDir.resolve("pretranscode_keep_abc.txt");
        Files.writeString(keepFile, keepId + "\n");

        service.cleanupOldFiles();

        assertTrue(Files.exists(keepDir));
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

    // ========== startAudioPass ==========

    @Test
    void startAudioPassUsesSegmentTimeWhenNoKeyframes() {
        when(ffprobeService.getKeyframes("/test/audio.mkv")).thenReturn(List.of());
        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        when(ffmpegMock.executeAsync()).thenReturn(completedFFmpegFuture());

        service.startAudioPass("/test/audio.mkv", tempDir, 0, AudioQuality.Q192K);

        verify(ffmpegMock).executeAsync();
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
}
