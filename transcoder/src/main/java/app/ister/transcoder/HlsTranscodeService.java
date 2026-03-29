package app.ister.transcoder;

import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Manages background FFmpeg transcoding passes, segment polling, and HLS cache cleanup.
 * <p>
 * A/V sync strategy: instead of running a separate FFmpeg process per segment (which
 * resets the AAC encoder and causes ~21 ms of drift per segment boundary), each quality
 * level gets a single continuous background FFmpeg pass using {@code -f segment}.
 * The first request for a transcoded segment starts the background pass; subsequent
 * requests either find the file already on disk (cache hit) or poll until the pass
 * has written the file.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HlsTranscodeService {

    private static final String FORMAT_MPEGTS = "mpegts";
    private static final String ARG_SEGMENT_TIMES = "-segment_times";
    private static final String ARG_SEGMENT_TIME_DELTA = "-segment_time_delta";

    private final Jaffree jaffree;
    private final FfprobeService ffprobeService;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @Value("${app.ister.server.hls.segment-timeout-ms:60000}")
    private long segmentTimeoutMs;

    @Value("${app.ister.transcoder.hls.hwaccel:none}")
    private String hwaccelProperty = "none";

    @Value("${app.ister.transcoder.hls.hwaccel-device:/dev/dri/renderD128}")
    private String hwaccelDevice = "/dev/dri/renderD128";

    @Value("${app.ister.transcoder.hls.max-concurrent-files:2}")
    private int maxConcurrentFiles;

    /**
     * Per-quality-level locks used by {@link #ensurePassStarted}.
     * Key format: "{mediaFileId}_video_{quality}" or "{mediaFileId}_audio_{streamIdx}_{bitrate}".
     */
    private final ConcurrentHashMap<String, Object> generationLocks = new ConcurrentHashMap<>();

    /** Tracks the in-progress background FFmpeg pass per quality level. */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeGenerations = new ConcurrentHashMap<>();

    /** Cached keyframes per file path — shared between video and audio passes to avoid redundant ffprobe calls. */
    private final ConcurrentHashMap<String, List<Double>> keyframeCache = new ConcurrentHashMap<>();

    /** Bounded thread pool for background FFmpeg passes (one pass per quality level). */
    private final ExecutorService transcodeExecutor = Executors.newFixedThreadPool(4);

    /** Limits the number of media files being transcoded simultaneously. Initialised in {@link #init()}. */
    Semaphore concurrentFileSlots;

    /** Number of active FFmpeg passes per media file ID. */
    private final ConcurrentHashMap<String, AtomicInteger> activePassesPerFile = new ConcurrentHashMap<>();

    /** Media file IDs that currently hold a semaphore slot. */
    private final Set<String> filesWithAcquiredSlot = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void init() {
        concurrentFileSlots = new Semaphore(maxConcurrentFiles);
    }

    // ========== Hardware acceleration ==========

    private UrlInput buildInput(String path) {
        String[] args = HardwareAccel.fromString(hwaccelProperty).inputArgs(hwaccelDevice);
        UrlInput input = UrlInput.fromUrl(path);
        for (int i = 0; i + 1 < args.length; i += 2) {
            input.addArguments(args[i], args[i + 1]);
        }
        return input;
    }

    // ========== Pass management ==========

    /**
     * Ensures a background FFmpeg pass is running for the given generation key.
     * Idempotent: if a pass is already in progress, this is a no-op.
     * If the previous pass failed or completed but the cache was cleared, a new pass is started.
     * <p>
     * The first pass for a media file acquires a slot from {@link #concurrentFileSlots}.
     * Subsequent passes for the same file reuse the same slot. The slot is released when
     * all passes for that file have completed.
     * <p>
     * Slot acquisition happens inside the executor thread, so HTTP handler threads are
     * never blocked. {@link #waitForSegment} will time out if a slot is not available in time.
     */
    public boolean isPassActive(String key) {
        CompletableFuture<Void> cf = activeGenerations.get(key);
        return cf != null && !cf.isDone();
    }

    public boolean hasCompletedPass(String key) {
        CompletableFuture<Void> cf = activeGenerations.get(key);
        return cf != null && cf.isDone() && !cf.isCompletedExceptionally();
    }

    public void ensurePassStarted(String generationKey, Runnable passStarter) {
        String mediaFileId = generationKey.split("_", 2)[0];
        Object lock = generationLocks.computeIfAbsent(generationKey, k -> new Object());
        synchronized (lock) {
            CompletableFuture<Void> existing = activeGenerations.get(generationKey);
            if (existing != null && !existing.isDone()) {
                return; // pass already running
            }
            if (existing != null && existing.isCompletedExceptionally()) {
                log.warn("Previous FFmpeg pass for {} failed, restarting", generationKey);
            }
            boolean isFirstPassForFile = activePassesPerFile
                    .computeIfAbsent(mediaFileId, k -> new AtomicInteger(0))
                    .getAndIncrement() == 0;
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> runPassWithSlot(mediaFileId, isFirstPassForFile, passStarter),
                    transcodeExecutor);
            activeGenerations.put(generationKey, future);
        }
    }

    private void runPassWithSlot(String mediaFileId, boolean isFirstPassForFile, Runnable passStarter) {
        if (isFirstPassForFile) {
            acquireTranscodeSlot(mediaFileId);
        }
        try {
            passStarter.run();
        } finally {
            releaseTranscodeSlotIfDone(mediaFileId);
        }
    }

    private void acquireTranscodeSlot(String mediaFileId) {
        try {
            log.debug("Waiting for transcode slot: {} (available: {})", mediaFileId, concurrentFileSlots.availablePermits());
            concurrentFileSlots.acquire();
            filesWithAcquiredSlot.add(mediaFileId);
            log.debug("Acquired transcode slot: {}", mediaFileId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AtomicInteger counter = activePassesPerFile.get(mediaFileId);
            if (counter != null) counter.decrementAndGet();
            throw new IllegalStateException("Interrupted waiting for transcode slot", e);
        }
    }

    private void releaseTranscodeSlotIfDone(String mediaFileId) {
        AtomicInteger counter = activePassesPerFile.get(mediaFileId);
        if (counter != null && counter.decrementAndGet() == 0) {
            if (filesWithAcquiredSlot.remove(mediaFileId)) {
                concurrentFileSlots.release();
                log.debug("Released transcode slot: {}", mediaFileId);
            }
            activePassesPerFile.remove(mediaFileId);
        }
    }

    /**
     * Starts a background video-only FFmpeg pass for the given quality.
     * Uses {@code -f segment -segment_times} so the encoder runs continuously,
     * eliminating the per-segment PTS reset that causes A/V drift.
     */
    public void startVideoPass(String inputPath, Path cacheDir, VideoQuality quality) {
        List<Double> keyframes = getCachedKeyframes(inputPath);
        String segmentTimes = buildSegmentTimes(keyframes);
        Path outputPattern = cacheDir.resolve("seg_video_" + quality.getLabel() + "_%05d.ts");

        log.debug("Starting video pass: quality={} keyframes={} hwaccel={}", quality.getLabel(), keyframes.size(), hwaccelProperty);

        HardwareAccel hw = HardwareAccel.fromString(hwaccelProperty);

        var output = UrlOutput.toUrl(outputPattern.toString())
                .setFormat("segment")
                .addArguments("-segment_format", FORMAT_MPEGTS)
                .addArguments("-map", "0:v:0")
                .addArgument("-an");

        if (quality == VideoQuality.COPY) {
            output.addArguments("-c:v", "copy");
            if (!segmentTimes.isEmpty()) {
                output.addArguments(ARG_SEGMENT_TIMES, segmentTimes);
                output.addArguments(ARG_SEGMENT_TIME_DELTA, "0.05");
            }
        } else {
            String codec = hw.encoder() != null ? hw.encoder() : quality.getCodec();
            String preset = hw.preset();
            output.addArguments("-c:v", codec)
                    .addArguments("-vf", hw.scaleFilter(quality.getScale()))
                    .addArguments("-b:v", quality.getBitrate());
            if (preset != null) {
                output.addArguments("-preset", preset);
            }
            if (!segmentTimes.isEmpty()) {
                output.addArguments("-force_key_frames", segmentTimes);
                output.addArguments(ARG_SEGMENT_TIMES, segmentTimes);
                output.addArguments(ARG_SEGMENT_TIME_DELTA, "0.05");
            }
        }

        jaffree.getFFMPEG()
                .addInput(buildInput(inputPath))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
    }

    /**
     * Starts a background audio-only FFmpeg pass for the given stream index and quality.
     * The encoder runs continuously across all segments, so PTS is never reset.
     */
    public void startAudioPass(String inputPath, Path cacheDir, int streamIdx, AudioQuality audioQuality) {
        List<Double> keyframes = getCachedKeyframes(inputPath);
        String segmentTimes = buildSegmentTimes(keyframes);
        Path outputPattern = cacheDir.resolve(
                String.format("seg_audio_%d_%s_%%05d.ts", streamIdx, audioQuality.getLabel()));

        log.debug("Starting audio pass: streamIdx={} quality={}", streamIdx, audioQuality.getLabel());

        var output = UrlOutput.toUrl(outputPattern.toString())
                .setFormat("segment")
                .addArguments("-segment_format", FORMAT_MPEGTS)
                .addArguments("-map", "0:" + streamIdx)
                .addArgument("-vn")
                .addArguments("-c:a", "aac")
                .addArguments("-ar", "48000")
                .addArguments("-b:a", audioQuality.getBitrate())
                .addArguments("-ac", "2");

        if (!segmentTimes.isEmpty()) {
            output.addArguments("-force_key_frames", segmentTimes);
            output.addArguments(ARG_SEGMENT_TIMES, segmentTimes);
            output.addArguments(ARG_SEGMENT_TIME_DELTA, "0.05");
        }

        jaffree.getFFMPEG()
                .addInput(UrlInput.fromUrl(inputPath))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
    }

    // ========== COPY-mode segment generators ==========

    void generateVideoSegment(String inputPath, Path outputPath, double start, double duration, VideoQuality quality) {
        HardwareAccel hw = HardwareAccel.fromString(hwaccelProperty);

        UrlOutput output = UrlOutput.toPath(outputPath)
                .setFormat(FORMAT_MPEGTS)
                .addArguments("-map", "0:v:0")
                .addArgument("-an");

        if (quality == VideoQuality.COPY) {
            output.addArguments("-c:v", "copy")
                    .addArguments("-t", String.format(Locale.ROOT, "%.6f", duration));
        } else {
            String codec = hw.encoder() != null ? hw.encoder() : quality.getCodec();
            String preset = hw.preset();
            output.addArguments("-t", String.format(Locale.ROOT, "%.6f", duration))
                    .addArguments("-c:v", codec)
                    .addArguments("-vf", hw.scaleFilter(quality.getScale()))
                    .addArguments("-b:v", quality.getBitrate());
            if (preset != null) {
                output.addArguments("-preset", preset);
            }
        }

        log.debug("Generating video segment: start={} duration={} quality={} hwaccel={} -> {}", start, duration, quality.getLabel(), hwaccelProperty, outputPath);
        jaffree.getFFMPEG()
                .addInput(buildInput(inputPath).addArguments("-ss", String.format(Locale.ROOT, "%.6f", start)))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
    }

    void generateAudioSegment(String inputPath, Path outputPath, double start, double duration, int audioIdx, AudioQuality quality) {
        UrlOutput output = UrlOutput.toPath(outputPath)
                .setFormat(FORMAT_MPEGTS)
                .addArgument("-vn")
                .addArguments("-map", "0:" + audioIdx);

        if (quality == AudioQuality.COPY) {
            output.addArguments("-c:a", "copy")
                    .addArguments("-t", String.format(Locale.ROOT, "%.6f", duration));
        } else {
            output.addArguments("-t", String.format(Locale.ROOT, "%.6f", duration))
                    .addArguments("-c:a", "aac")
                    .addArguments("-ar", "48000")
                    .addArguments("-b:a", quality.getBitrate())
                    .addArguments("-ac", "2");
        }

        log.debug("Generating audio segment: start={} duration={} idx={} quality={} -> {}", start, duration, audioIdx, quality.getLabel(), outputPath);
        jaffree.getFFMPEG()
                .addInput(UrlInput.fromUrl(inputPath).addArguments("-ss", String.format(Locale.ROOT, "%.6f", start)))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
    }

    // ========== Segment waiting ==========

    /**
     * Polls until the segment file is fully written or the background pass has completed.
     * <p>
     * Fast path: if the pass is done (normally), all files are fully written — return immediately.
     * Slow path: the pass is still running; check for stable file size (FFmpeg closes segment N
     * before opening segment N+1, so two reads 200 ms apart with equal size means "done").
     *
     * @throws IOException on timeout or pass failure
     */
    Path waitForSegment(Path segmentPath, String generationKey) throws IOException {
        long deadline = System.currentTimeMillis() + segmentTimeoutMs;
        long delay = 50;

        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("Timeout waiting for HLS segment: " + segmentPath);
            }

            CompletableFuture<Void> future = activeGenerations.get(generationKey);

            if (future != null && future.isDone()) {
                return resolveCompletedSegment(future, segmentPath, generationKey);
            }

            // Pass still running: check if FFmpeg has already closed this segment
            Path stable = stableSegmentOrNull(segmentPath);
            if (stable != null) return stable;

            try {
                Thread.sleep(Math.clamp(deadline - System.currentTimeMillis(), 10, delay));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for segment");
            }
            delay = Math.min(delay * 2, 500);
        }
    }

    private Path resolveCompletedSegment(CompletableFuture<Void> future, Path segmentPath, String generationKey) throws IOException {
        if (future.isCompletedExceptionally()) {
            throw new IOException("FFmpeg pass failed for: " + generationKey);
        }
        // Pass completed normally — all segments are fully written
        if (Files.exists(segmentPath) && Files.size(segmentPath) > 0) {
            Files.setLastModifiedTime(segmentPath, FileTime.fromMillis(System.currentTimeMillis()));
            return segmentPath;
        }
        throw new IOException("Segment not produced by FFmpeg pass: " + segmentPath);
    }

    /**
     * Returns the path if the segment file exists and has a stable (non-zero) size
     * after a 200 ms double-check, indicating FFmpeg has fully closed the file.
     * Returns {@code null} if the file does not exist, is empty, or is still being written.
     */
    Path stableSegmentOrNull(Path segmentPath) throws IOException {
        if (Files.exists(segmentPath) && Files.size(segmentPath) > 0) {
            long size1 = Files.size(segmentPath);
            try {
                Thread.sleep(200);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for segment");
            }
            if (Files.exists(segmentPath) && Files.size(segmentPath) == size1) {
                Files.setLastModifiedTime(segmentPath, FileTime.fromMillis(System.currentTimeMillis()));
                return segmentPath;
            }
        }
        return null;
    }

    List<Double> getCachedKeyframes(String filePath) {
        return keyframeCache.computeIfAbsent(filePath, ffprobeService::getKeyframes);
    }

    double getTotalDuration(String filePath) {
        return ffprobeService.getTotalDuration(filePath);
    }

    private String buildSegmentTimes(List<Double> keyframes) {
        return keyframes.stream()
                .skip(1) // skip 0.0; FFmpeg cuts at these positions
                .map(t -> String.format(Locale.ROOT, "%.6f", t))
                .collect(Collectors.joining(","));
    }

    // ========== Scheduled cleanup ==========

    @Scheduled(cron = "0 */15 * * * *")
    public void cleanupOldFiles() {
        Path root = Paths.get(tmpDir);
        if (!Files.exists(root)) return;

        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try (var entries = Files.list(dir)) {
                    boolean allOld = entries.allMatch(f -> isOlderThan(f, twoHoursAgo));
                    if (allOld) {
                        log.debug("Removing stale HLS cache: {}", dir);
                        try (var walk = Files.walk(dir)) {
                            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    log.warn("Could not delete {}", p, e);
                                }
                            });
                        }
                        String staleMediaFileId = dir.getFileName().toString();
                        activeGenerations.keySet().removeIf(k -> k.startsWith(staleMediaFileId));
                        generationLocks.keySet().removeIf(k -> k.startsWith(staleMediaFileId));
                        if (filesWithAcquiredSlot.remove(staleMediaFileId)) {
                            concurrentFileSlots.release();
                            log.warn("Released stale transcode slot during cleanup: {}", staleMediaFileId);
                        }
                        activePassesPerFile.remove(staleMediaFileId);
                    }
                } catch (IOException e) {
                    log.warn("Error scanning cache dir {}", dir, e);
                }
            });
        } catch (IOException e) {
            log.warn("Error during HLS cache cleanup", e);
        }
    }

    private boolean isOlderThan(Path path, Instant threshold) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(threshold);
        } catch (IOException _) {
            return false;
        }
    }
}
