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
    private static final String AUDIO_SAMPLE_RATE = "48000";
    /** The codec the AAC transcode qualities re-encode to; a source already in this codec is copied instead. */
    private static final String AAC_CODEC = "aac";
    private static final long PASS_POLL_MS = 250;

    private static final Set<String> MPEGTS_NATIVE_AUDIO_CODECS = Set.of("aac", "mp3", "ac3", "eac3", "dts");

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

    /** Upper bound on concurrent FFmpeg passes across all files (one pass per quality level). */
    @Value("${app.ister.transcoder.hls.max-concurrent-passes:4}")
    private int maxConcurrentPasses;

    /** A segment is considered fully written when its size is unchanged for this long. */
    @Value("${app.ister.transcoder.hls.segment-stability-ms:200}")
    private long segmentStabilityMs;

    /** A pass is force-stopped after {@code max(duration * multiplier, minimum)} to reap hung FFmpeg processes. */
    @Value("${app.ister.transcoder.hls.pass-timeout-multiplier:4}")
    private double passTimeoutMultiplier;

    @Value("${app.ister.transcoder.hls.pass-timeout-min-seconds:1800}")
    private long passTimeoutMinSeconds;

    /**
     * A pass that has not written a single segment within this window is force-stopped as
     * "stalled". This reaps an FFmpeg that hangs on a specific input (emitting zero bytes)
     * long before the much larger {@link #passTimeoutMinSeconds}, so the waiting segment
     * request fails fast instead of holding the connection open for the whole segment timeout.
     */
    @Value("${app.ister.transcoder.hls.pass-stall-timeout-seconds:60}")
    private long passStallTimeoutSeconds;

    /** HLS cache directories untouched for this long are removed by the cleanup job. */
    @Value("${app.ister.transcoder.hls.cache-retention-hours:2}")
    private long cacheRetentionHours;

    /**
     * Per-quality-level locks used by {@link #ensurePassStarted}.
     * Key format: "{mediaFileId}_video_{quality}" or "{mediaFileId}_audio_{streamIdx}_{bitrate}".
     */
    private final ConcurrentHashMap<String, Object> generationLocks = new ConcurrentHashMap<>();

    /** Tracks the in-progress background FFmpeg pass per quality level. */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeGenerations = new ConcurrentHashMap<>();

    /** Cached keyframes per file path — shared between video and audio passes to avoid redundant ffprobe calls. */
    private final ConcurrentHashMap<String, List<Double>> keyframeCache = new ConcurrentHashMap<>();

    /** Cached total duration per file path — used to derive the pass timeout. */
    private final ConcurrentHashMap<String, Double> durationCache = new ConcurrentHashMap<>();

    /** Bounded thread pool for background FFmpeg passes (one pass per quality level). Initialised in {@link #init()}. */
    ExecutorService transcodeExecutor;

    /** Limits the number of media files being transcoded simultaneously. Initialised in {@link #init()}. */
    Semaphore concurrentFileSlots;

    /** Segments observed with a stable size; lets repeat requests skip the stability window. */
    private final Set<String> knownStableSegments = ConcurrentHashMap.newKeySet();

    /** Last observed {size, epochMillis} per still-growing segment, for the non-blocking stability check. */
    private final ConcurrentHashMap<String, long[]> segmentSizeSamples = new ConcurrentHashMap<>();

    /** Number of active FFmpeg passes per media file ID. */
    private final ConcurrentHashMap<String, AtomicInteger> activePassesPerFile = new ConcurrentHashMap<>();

    /** Media file IDs that currently hold a semaphore slot. */
    private final Set<String> filesWithAcquiredSlot = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void init() {
        concurrentFileSlots = new Semaphore(maxConcurrentFiles);
        transcodeExecutor = Executors.newFixedThreadPool(maxConcurrentPasses);
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

    public CompletableFuture<Void> getActiveFuture(String key) {
        return activeGenerations.get(key);
    }

    public boolean hasCompletedPass(String key) {
        CompletableFuture<Void> cf = activeGenerations.get(key);
        return cf != null && cf.isDone() && !cf.isCompletedExceptionally();
    }

    public boolean hasFailedPass(String key) {
        CompletableFuture<Void> cf = activeGenerations.get(key);
        return cf != null && cf.isCompletedExceptionally();
    }

    public boolean hasAnyActiveOrCompletedPassForFile(UUID mediaFileId) {
        String prefix = mediaFileId.toString() + "_";
        return activeGenerations.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .anyMatch(e -> {
                    CompletableFuture<Void> cf = e.getValue();
                    return !cf.isDone() || (cf.isDone() && !cf.isCompletedExceptionally());
                });
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
            // The file slot is acquired on a virtual thread, NOT inside the pass pool: if
            // slot-waiters occupied pool threads, a full pool of waiting first-passes would
            // starve the queued passes of the slot-holding files (deadlock until timeout).
            CompletableFuture<Void> future = new CompletableFuture<>();
            Thread.startVirtualThread(() -> runPassWithSlot(mediaFileId, isFirstPassForFile, passStarter, future));
            activeGenerations.put(generationKey, future);
        }
    }

    private void runPassWithSlot(String mediaFileId, boolean isFirstPassForFile, Runnable passStarter,
                                 CompletableFuture<Void> future) {
        try {
            if (isFirstPassForFile) {
                acquireTranscodeSlot(mediaFileId);
            }
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            return;
        }
        try {
            transcodeExecutor.submit(() -> {
                try {
                    passStarter.run();
                    future.complete(null);
                } catch (Exception t) {
                    future.completeExceptionally(t);
                } finally {
                    releaseTranscodeSlotIfDone(mediaFileId);
                }
            });
        } catch (RuntimeException e) { // executor shut down
            releaseTranscodeSlotIfDone(mediaFileId);
            future.completeExceptionally(e);
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
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create cache dir: " + cacheDir, e);
        }
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

        executePassWithTimeout(jaffree.getFFMPEG()
                .addInput(buildInput(inputPath))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR),
                inputPath, "video pass " + quality.getLabel(),
                cacheDir, "seg_video_" + quality.getLabel() + "_");
    }

    /**
     * Starts a background audio-only FFmpeg pass for the given stream index and quality.
     * The encoder runs continuously across all segments, so PTS is never reset.
     * <p>
     * When the source stream is already AAC (the codec the transcode qualities target) and
     * therefore MPEG-TS-native, the audio is stream-copied instead of re-encoded. This both
     * saves CPU and avoids re-encoding an audio track through the AAC encoder — a path that
     * can hang indefinitely on some inputs (e.g. an audio file carrying an embedded cover-art
     * picture), whereas the copy path handles the same source without issue.
     */
    public void startAudioPass(String inputPath, Path cacheDir, int streamIdx, AudioQuality audioQuality,
                               String sourceCodecName) {
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create cache dir: " + cacheDir, e);
        }
        List<Double> keyframes = getCachedKeyframes(inputPath);
        String segmentTimes = buildSegmentTimes(keyframes);
        Path outputPattern = cacheDir.resolve(
                String.format("seg_audio_%d_%s_%%05d.ts", streamIdx, audioQuality.getLabel()));

        boolean copySource = AAC_CODEC.equalsIgnoreCase(sourceCodecName);
        log.debug("Starting audio pass: streamIdx={} quality={} sourceCodec={} copy={}",
                streamIdx, audioQuality.getLabel(), sourceCodecName, copySource);

        // -vn guarantees an attached cover-art picture can never pull FFmpeg into a video
        // encode/mux path; only the explicitly mapped audio stream is written.
        var output = UrlOutput.toUrl(outputPattern.toString())
                .setFormat("segment")
                .addArguments("-segment_format", FORMAT_MPEGTS)
                .addArguments("-map", "0:" + streamIdx)
                .addArgument("-vn");

        if (copySource) {
            output.addArguments("-c:a", "copy");
        } else {
            output.addArguments("-c:a", AAC_CODEC)
                    .addArguments("-ar", AUDIO_SAMPLE_RATE)
                    .addArguments("-b:a", audioQuality.getBitrate())
                    .addArguments("-ac", "2");
        }

        if (!segmentTimes.isEmpty()) {
            if (!copySource) {
                // -force_key_frames drives the AAC encoder to align frames with the cut points;
                // it is meaningless (and rejected) with -c:a copy.
                output.addArguments("-force_key_frames", segmentTimes);
            }
            output.addArguments(ARG_SEGMENT_TIMES, segmentTimes);
            output.addArguments(ARG_SEGMENT_TIME_DELTA, "0.05");
        } else {
            output.addArguments("-segment_time", "10");
        }

        executePassWithTimeout(jaffree.getFFMPEG()
                .addInput(UrlInput.fromUrl(inputPath))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR),
                inputPath, "audio pass " + streamIdx + "/" + audioQuality.getLabel(),
                cacheDir, String.format("seg_audio_%d_%s_", streamIdx, audioQuality.getLabel()));
    }

    /**
     * Runs a whole-file FFmpeg pass with two time bounds: an overall bound derived from the media
     * duration, and a much shorter stall bound. A pass that never writes a segment is force-stopped
     * after {@link #passStallTimeoutSeconds} so a hung FFmpeg (emitting zero bytes for a specific
     * input) is reaped promptly instead of holding its pool thread and slot for the full duration
     * bound. The pass future then completes exceptionally, so the waiting segment request fails fast.
     */
    private void executePassWithTimeout(com.github.kokorin.jaffree.ffmpeg.FFmpeg ffmpeg, String inputPath,
                                        String what, Path cacheDir, String segmentPrefix) {
        long timeoutSeconds = Math.max((long) (getTotalDuration(inputPath) * passTimeoutMultiplier), passTimeoutMinSeconds);
        var future = ffmpeg.executeAsync();
        long overallDeadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        long stallDeadline = System.currentTimeMillis() + passStallTimeoutSeconds * 1000;
        try {
            while (true) {
                try {
                    future.get(PASS_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                    return; // pass completed normally
                } catch (java.util.concurrent.TimeoutException _) {
                    long now = System.currentTimeMillis();
                    if (now >= overallDeadline) {
                        future.forceStop();
                        throw new IllegalStateException("FFmpeg timed out after " + timeoutSeconds + "s: " + what);
                    }
                    if (now >= stallDeadline && !hasProducedSegment(cacheDir, segmentPrefix)) {
                        future.forceStop();
                        throw new IllegalStateException(
                                "FFmpeg produced no output within " + passStallTimeoutSeconds + "s (stalled): " + what);
                    }
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("FFmpeg failed: " + what, e.getCause());
        } catch (InterruptedException e) {
            future.forceStop();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during FFmpeg run: " + what, e);
        }
    }

    /** True if the pass has already written at least one non-empty segment (so it is not stalled). */
    private boolean hasProducedSegment(Path cacheDir, String segmentPrefix) {
        if (cacheDir == null || segmentPrefix == null) return true; // no info to judge by — never false-trip
        try (var files = Files.list(cacheDir)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                if (!name.startsWith(segmentPrefix) || !name.endsWith(".ts")) return false;
                try {
                    return Files.size(p) > 0;
                } catch (IOException _) {
                    return false;
                }
            });
        } catch (IOException _) {
            return true; // cannot list the dir — don't kill a possibly-healthy pass
        }
    }

    private void executeWithTimeout(com.github.kokorin.jaffree.ffmpeg.FFmpeg ffmpeg, long timeoutSeconds, String what) {
        var future = ffmpeg.executeAsync();
        try {
            future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException _) {
            future.forceStop();
            throw new IllegalStateException("FFmpeg timed out after " + timeoutSeconds + "s: " + what);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("FFmpeg failed: " + what, e.getCause());
        } catch (InterruptedException e) {
            future.forceStop();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during FFmpeg run: " + what, e);
        }
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
        executeWithTimeout(jaffree.getFFMPEG()
                .addInput(buildInput(inputPath).addArguments("-ss", String.format(Locale.ROOT, "%.6f", start)))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR),
                Math.max(60, segmentTimeoutMs / 1000), "video segment " + outputPath.getFileName());
    }

    void generateAudioSegment(String inputPath, Path outputPath, double start, double duration, int audioIdx, AudioQuality quality, String sourceCodecName) {
        UrlOutput output = UrlOutput.toPath(outputPath)
                .setFormat(FORMAT_MPEGTS)
                .addArgument("-vn")
                .addArguments("-map", "0:" + audioIdx);

        if (quality == AudioQuality.COPY && MPEGTS_NATIVE_AUDIO_CODECS.contains(sourceCodecName.toLowerCase())) {
            output.addArguments("-c:a", "copy")
                    .addArguments("-t", String.format(Locale.ROOT, "%.6f", duration));
        } else if (quality == AudioQuality.COPY) {
            // Non-MPEG-TS-native codec (e.g. FLAC, ALAC): transcode to AAC
            log.debug("Codec '{}' cannot be copied into MPEG-TS, transcoding to AAC", sourceCodecName);
            output.addArguments("-t", String.format(Locale.ROOT, "%.6f", duration))
                    .addArguments("-c:a", "aac")
                    .addArguments("-ar", AUDIO_SAMPLE_RATE)
                    .addArguments("-b:a", "192k")
                    .addArguments("-ac", "2");
        } else {
            output.addArguments("-t", String.format(Locale.ROOT, "%.6f", duration))
                    .addArguments("-c:a", "aac")
                    .addArguments("-ar", AUDIO_SAMPLE_RATE)
                    .addArguments("-b:a", quality.getBitrate())
                    .addArguments("-ac", "2");
        }

        log.debug("Generating audio segment: start={} duration={} idx={} quality={} -> {}", start, duration, audioIdx, quality.getLabel(), outputPath);
        executeWithTimeout(jaffree.getFFMPEG()
                .addInput(UrlInput.fromUrl(inputPath).addArguments("-ss", String.format(Locale.ROOT, "%.6f", start)))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR),
                Math.max(60, segmentTimeoutMs / 1000), "audio segment " + outputPath.getFileName());
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
     * Returns the path if the segment file exists and its (non-zero) size has been stable
     * for at least {@code segment-stability-ms}, indicating FFmpeg has fully closed the file.
     * Returns {@code null} if the file does not exist, is empty, or is still being written.
     * <p>
     * Non-blocking: instead of sleeping between two size reads (which used to stall the
     * calling HTTP/watcher thread for 200 ms per call), the last observed size is remembered
     * per path and compared on the next call — callers already poll on their own schedule.
     * Once a segment has been seen stable it is remembered, so repeat requests return instantly.
     */
    Path stableSegmentOrNull(Path segmentPath) throws IOException {
        String key = segmentPath.toString();
        if (knownStableSegments.contains(key)) {
            if (Files.exists(segmentPath)) {
                Files.setLastModifiedTime(segmentPath, FileTime.fromMillis(System.currentTimeMillis()));
                return segmentPath;
            }
            knownStableSegments.remove(key); // cache dir was cleaned up
            return null;
        }
        if (!Files.exists(segmentPath) || Files.size(segmentPath) == 0) {
            return null;
        }
        long size = Files.size(segmentPath);
        long now = System.currentTimeMillis();
        long[] previous = segmentSizeSamples.get(key);
        if (previous == null || previous[0] != size) {
            segmentSizeSamples.put(key, new long[]{size, now}); // (re)start the stability window
            return null;
        }
        if (now - previous[1] >= segmentStabilityMs) {
            segmentSizeSamples.remove(key);
            knownStableSegments.add(key);
            Files.setLastModifiedTime(segmentPath, FileTime.fromMillis(now));
            return segmentPath;
        }
        return null;
    }

    List<Double> getCachedKeyframes(String filePath) {
        return keyframeCache.computeIfAbsent(filePath, ffprobeService::getKeyframes);
    }

    double getTotalDuration(String filePath) {
        return durationCache.computeIfAbsent(filePath, ffprobeService::getTotalDuration);
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

        Set<String> keepIds = loadKeepFileIds(root);
        Instant retentionThreshold = Instant.now().minus(cacheRetentionHours, ChronoUnit.HOURS);
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                String dirName = dir.getFileName().toString();
                if (keepIds.contains(dirName)) {
                    log.debug("Skipping pre-transcode kept dir: {}", dir);
                    return;
                }
                try (var entries = Files.list(dir)) {
                    boolean allOld = entries.allMatch(f -> isOlderThan(f, retentionThreshold));
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
                        activeGenerations.keySet().removeIf(k -> k.startsWith(dirName));
                        generationLocks.keySet().removeIf(k -> k.startsWith(dirName));
                        knownStableSegments.removeIf(k -> k.contains(dirName));
                        segmentSizeSamples.keySet().removeIf(k -> k.contains(dirName));
                        if (filesWithAcquiredSlot.remove(dirName)) {
                            concurrentFileSlots.release();
                            log.warn("Released stale transcode slot during cleanup: {}", dirName);
                        }
                        activePassesPerFile.remove(dirName);
                    }
                } catch (IOException e) {
                    log.warn("Error scanning cache dir {}", dir, e);
                }
            });
        } catch (IOException e) {
            log.warn("Error during HLS cache cleanup", e);
        }
    }

    private Set<String> loadKeepFileIds(Path root) {
        try (var files = Files.list(root)) {
            return files
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().startsWith("pretranscode_keep_")
                              && p.getFileName().toString().endsWith(".txt"))
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p).stream();
                        } catch (IOException e) {
                            log.warn("Could not read keep file {}", p, e);
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            log.warn("Could not read pretranscode keep files", e);
            return Set.of();
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
