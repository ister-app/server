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

    /** Marker file written after a pass finished successfully: {@code done_<segmentPrefix>}. */
    private static final String DONE_MARKER_PREFIX = "done_";

    private static final String SEG_VIDEO_PREFIX = "seg_video_";

    /** Per-cache-dir file holding the epoch-millis timestamp until which the dir must be kept. */
    private static final String KEEP_UNTIL_FILE = "keep_until";

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

    /** Of the {@code max-concurrent-files} slots, background (pre-transcode/prefetch) work may hold at most this many. */
    @Value("${app.ister.transcoder.hls.max-background-files:1}")
    private int maxBackgroundFiles;

    /** Of the {@code max-concurrent-passes} threads, background work may hold at most this many. */
    @Value("${app.ister.transcoder.hls.max-background-passes:2}")
    private int maxBackgroundPasses;

    /**
     * OS niceness for background FFmpeg processes (0 = run at normal priority). The kernel then
     * only grants a background encode the CPU cycles interactive encodes leave unused, on top of
     * the admission/preemption rules. Applied via a generated {@code nice}-wrapper script; when
     * {@code nice} is unavailable the wrapper is disabled and background runs at normal priority.
     */
    @Value("${app.ister.transcoder.hls.background-nice:10}")
    private int backgroundNice;

    /** Absolute path of the {@code nice} binary; absolute so the wrapper never depends on PATH. */
    @Value("${app.ister.transcoder.hls.nice-path:/usr/bin/nice}")
    private String nicePath;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String ffmpegDir;

    /** Directory holding the nice-wrapper `ffmpeg` script; null when the wrapper is disabled. */
    private Path niceWrapperDir;

    /** Name of the wrapper directory inside {@link #tmpDir}; skipped by the cleanup job. */
    private static final String NICE_WRAPPER_DIR_NAME = "ffmpeg-nice";

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

    /** Limits the file slots background work may hold. Initialised in {@link #init()}. */
    Semaphore backgroundFileBudget;

    /** Limits the executor threads background passes may hold. Initialised in {@link #init()}. */
    Semaphore backgroundPassBudget;

    /** Media file IDs whose slot was acquired under the background budget (preemptable). */
    private final Set<String> filesWithBackgroundBudget = ConcurrentHashMap.newKeySet();

    /** Media file IDs preempted in favour of interactive work; queued background passes for these skip FFmpeg. */
    private final Set<String> preemptedFiles = ConcurrentHashMap.newKeySet();

    /** Generation keys whose running pass was individually preempted (removed from activeGenerations on failure). */
    private final Set<String> preemptedKeys = ConcurrentHashMap.newKeySet();

    /** Bookkeeping for a pass that is currently executing on a pool thread. */
    private static final class RunningPass {
        final String generationKey;
        final String mediaFileId;
        final boolean background;
        final long startedAtMillis;
        private Thread thread;

        RunningPass(String generationKey, String mediaFileId, boolean background, long startedAtMillis) {
            this.generationKey = generationKey;
            this.mediaFileId = mediaFileId;
            this.background = background;
            this.startedAtMillis = startedAtMillis;
        }

        synchronized void setThread(Thread thread) {
            this.thread = thread;
        }

        /** Clears the thread so a preemptor can no longer interrupt it (the pass is finishing). */
        synchronized void clearThread() {
            this.thread = null;
        }

        /** Interrupts the pass thread if it is still running; false when the pass already finished. */
        synchronized boolean interruptIfRunning() {
            if (thread == null) {
                return false;
            }
            thread.interrupt();
            return true;
        }
    }

    /** Passes currently executing on a pool thread, by generation key. */
    private final ConcurrentHashMap<String, RunningPass> runningPasses = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        concurrentFileSlots = new Semaphore(maxConcurrentFiles);
        transcodeExecutor = Executors.newFixedThreadPool(maxConcurrentPasses);
        backgroundFileBudget = new Semaphore(Math.min(maxBackgroundFiles, maxConcurrentFiles));
        backgroundPassBudget = new Semaphore(Math.min(maxBackgroundPasses, maxConcurrentPasses));
        niceWrapperDir = createNiceWrapper();
    }

    // ========== Background niceness ==========

    /**
     * Writes a wrapper script that starts FFmpeg under {@code nice -n <backgroundNice>}, in a
     * fresh temp directory (Jaffree resolves {@code <dir>/ffmpeg}, so the script simply shadows
     * the binary's name). Probes {@code nice} first; any failure disables the wrapper so
     * background passes still run, just at normal priority.
     *
     * @return the wrapper directory, or null when disabled or unavailable
     */
    Path createNiceWrapper() {
        if (backgroundNice <= 0) {
            return null;
        }
        try {
            Process probe = new ProcessBuilder(nicePath, "-n", String.valueOf(backgroundNice), "true")
                    .redirectErrorStream(true)
                    .start();
            if (!probe.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || probe.exitValue() != 0) {
                probe.destroyForcibly();
                log.warn("'{} -n {}' probe failed; background FFmpeg passes will run at normal priority", nicePath, backgroundNice);
                return null;
            }
            // Lives in the app-owned cache dir (not the world-writable system temp dir);
            // the cleanup job skips it by name. Script and dir are owner-only.
            Path dir = Paths.get(tmpDir, NICE_WRAPPER_DIR_NAME);
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Path script = dir.resolve("ffmpeg");
            String realFfmpeg = Paths.get(ffmpegDir).resolve("ffmpeg").toString();
            Files.writeString(script, "#!/bin/sh\nexec " + nicePath + " -n " + backgroundNice + " " + realFfmpeg + " \"$@\"\n");
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            log.info("Background FFmpeg passes run with nice -n {} via {}", backgroundNice, script);
            return dir;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while probing 'nice'; background FFmpeg passes will run at normal priority");
            return null;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not set up nice wrapper; background FFmpeg passes will run at normal priority", e);
            return null;
        }
    }

    /** FFmpeg builder for a pass: background passes use the nice-wrapper when available. */
    com.github.kokorin.jaffree.ffmpeg.FFmpeg ffmpegFor(boolean background) {
        if (background && niceWrapperDir != null) {
            return com.github.kokorin.jaffree.ffmpeg.FFmpeg.atPath(niceWrapperDir);
        }
        return jaffree.getFFMPEG();
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
        ensurePassStarted(generationKey, passStarter, false);
    }

    /**
     * @param background pre-transcode/prefetch work: only runs on spare capacity (never blocks
     *                   waiting for a slot) and may be preempted when interactive work needs
     *                   its slot or thread.
     */
    public void ensurePassStarted(String generationKey, Runnable passStarter, boolean background) {
        String mediaFileId = generationKey.split("_", 2)[0];
        Object lock = generationLocks.computeIfAbsent(generationKey, k -> new Object());
        synchronized (lock) {
            CompletableFuture<Void> existing = activeGenerations.get(generationKey);
            if (existing != null && !existing.isDone()) {
                return; // pass already running
            }
            if (existing != null && existing.isCompletedExceptionally()) {
                if (background) {
                    // Don't burn background budget retrying an input that already failed;
                    // an interactive request may still force a restart below.
                    log.debug("Skipping background restart of previously failed pass {}", generationKey);
                    return;
                }
                log.warn("Previous FFmpeg pass for {} failed, restarting", generationKey);
            }
            // An interactive request for a file whose slot was acquired as background promotes
            // the file: it keeps its slot but is no longer preemptable, and the budget frees up.
            if (!background && filesWithBackgroundBudget.remove(mediaFileId)) {
                preemptedFiles.remove(mediaFileId);
                backgroundFileBudget.release();
            }
            boolean isFirstPassForFile = activePassesPerFile
                    .computeIfAbsent(mediaFileId, k -> new AtomicInteger(0))
                    .getAndIncrement() == 0;
            CompletableFuture<Void> future = new CompletableFuture<>();
            activeGenerations.put(generationKey, future);
            if (background) {
                // Slot acquisition is non-blocking for background work, so it happens inline:
                // by the time a sibling pass of the same file is registered, the file either
                // holds its slot or this pass (and its counter increment) is already gone.
                if (isFirstPassForFile && !tryAcquireBackgroundSlot(mediaFileId)) {
                    dropBackgroundPass(generationKey, mediaFileId, future, "no background file slot available");
                    return;
                }
                submitPass(generationKey, mediaFileId, true, passStarter, future);
            } else {
                // The file slot is acquired on a virtual thread, NOT inside the pass pool: if
                // slot-waiters occupied pool threads, a full pool of waiting first-passes would
                // starve the queued passes of the slot-holding files (deadlock until timeout).
                Thread.startVirtualThread(() -> runPassWithSlot(generationKey, mediaFileId, isFirstPassForFile, passStarter, future));
            }
        }
    }

    private void runPassWithSlot(String generationKey, String mediaFileId, boolean isFirstPassForFile,
                                 Runnable passStarter, CompletableFuture<Void> future) {
        try {
            if (isFirstPassForFile) {
                acquireTranscodeSlotPreempting(mediaFileId);
            }
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            return;
        }
        preemptBackgroundPassForThreadIfNeeded();
        submitPass(generationKey, mediaFileId, false, passStarter, future);
    }

    private void submitPass(String generationKey, String mediaFileId, boolean background,
                            Runnable passStarter, CompletableFuture<Void> future) {
        try {
            transcodeExecutor.submit(() -> runPass(generationKey, mediaFileId, background, passStarter, future));
        } catch (RuntimeException e) { // executor shut down
            releaseTranscodeSlotIfDone(mediaFileId);
            future.completeExceptionally(e);
        }
    }

    private void runPass(String generationKey, String mediaFileId, boolean background,
                         Runnable passStarter, CompletableFuture<Void> future) {
        if (background && preemptedFiles.contains(mediaFileId)) {
            // Queued pass of a file that was preempted while this task waited for a thread:
            // don't start FFmpeg, just drain the bookkeeping so the slot frees up quickly.
            dropBackgroundPass(generationKey, mediaFileId, future, "file was preempted by interactive work");
            return;
        }
        if (background && !backgroundPassBudget.tryAcquire()) {
            dropBackgroundPass(generationKey, mediaFileId, future, "background pass budget exhausted");
            return;
        }
        RunningPass runningPass = new RunningPass(generationKey, mediaFileId, background, System.currentTimeMillis());
        runningPass.setThread(Thread.currentThread());
        runningPasses.put(generationKey, runningPass);
        try {
            passStarter.run();
            future.complete(null);
        } catch (Exception t) {
            if (preemptedKeys.remove(generationKey)) {
                // Preempted, not failed: forget the pass so a later event can restart it.
                activeGenerations.remove(generationKey);
                log.info("Background pass {} preempted by interactive work", generationKey);
            }
            future.completeExceptionally(t);
        } finally {
            runningPass.clearThread();
            Thread.interrupted(); // clear a leaked preemption interrupt before the pool thread is reused
            runningPasses.remove(generationKey);
            if (background) {
                backgroundPassBudget.release();
            }
            releaseTranscodeSlotIfDone(mediaFileId);
        }
    }

    /**
     * Drops a background pass that cannot (or should no longer) run. The generation key is
     * removed so a later scheduler/prefetch event simply retries; nothing is queued.
     */
    private void dropBackgroundPass(String generationKey, String mediaFileId, CompletableFuture<Void> future, String reason) {
        log.info("Dropping background pass {}: {}", generationKey, reason);
        activeGenerations.remove(generationKey);
        future.completeExceptionally(new IllegalStateException("Background pass dropped: " + reason));
        releaseTranscodeSlotIfDone(mediaFileId);
    }

    private boolean tryAcquireBackgroundSlot(String mediaFileId) {
        if (!backgroundFileBudget.tryAcquire()) {
            return false;
        }
        if (!concurrentFileSlots.tryAcquire()) {
            backgroundFileBudget.release();
            return false;
        }
        filesWithBackgroundBudget.add(mediaFileId);
        filesWithAcquiredSlot.add(mediaFileId);
        log.debug("Acquired background transcode slot: {}", mediaFileId);
        return true;
    }

    /**
     * Interactive slot acquisition: when no slot is free, the youngest background file is
     * preempted (its running passes are interrupted, which releases its slot) before falling
     * back to the normal blocking acquire.
     */
    private void acquireTranscodeSlotPreempting(String mediaFileId) {
        if (concurrentFileSlots.tryAcquire()) {
            filesWithAcquiredSlot.add(mediaFileId);
            log.debug("Acquired transcode slot: {}", mediaFileId);
            return;
        }
        preemptYoungestBackgroundFile();
        acquireTranscodeSlot(mediaFileId);
    }

    private void preemptYoungestBackgroundFile() {
        runningPasses.values().stream()
                .filter(pass -> pass.background && filesWithBackgroundBudget.contains(pass.mediaFileId))
                .max(Comparator.comparingLong(pass -> pass.startedAtMillis))
                .ifPresent(youngest -> {
                    log.info("Preempting background transcode of {} for interactive playback", youngest.mediaFileId);
                    preemptedFiles.add(youngest.mediaFileId);
                    runningPasses.values().stream()
                            .filter(pass -> pass.mediaFileId.equals(youngest.mediaFileId))
                            .forEach(this::preemptPass);
                });
    }

    /**
     * When the pass pool is saturated and a background pass is running, interrupt the youngest
     * background pass so the interactive pass that is about to be submitted gets its thread.
     */
    private void preemptBackgroundPassForThreadIfNeeded() {
        if (runningPasses.size() < maxConcurrentPasses) {
            return;
        }
        runningPasses.values().stream()
                .filter(pass -> pass.background)
                .max(Comparator.comparingLong(pass -> pass.startedAtMillis))
                .ifPresent(this::preemptPass);
    }

    private void preemptPass(RunningPass pass) {
        // Key is marked before the interrupt so the pass's failure handler always sees it;
        // when the pass turns out to have finished already, the mark is rolled back.
        preemptedKeys.add(pass.generationKey);
        if (!pass.interruptIfRunning()) {
            preemptedKeys.remove(pass.generationKey);
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
            if (filesWithBackgroundBudget.remove(mediaFileId)) {
                backgroundFileBudget.release();
            }
            preemptedFiles.remove(mediaFileId);
            activePassesPerFile.remove(mediaFileId);
        }
    }

    /**
     * Starts a background video-only FFmpeg pass for the given quality.
     * Uses {@code -f segment -segment_times} so the encoder runs continuously,
     * eliminating the per-segment PTS reset that causes A/V drift.
     */
    public void startVideoPass(String inputPath, Path cacheDir, VideoQuality quality) {
        startVideoPass(inputPath, cacheDir, quality, false);
    }

    public void startVideoPass(String inputPath, Path cacheDir, VideoQuality quality, boolean background) {
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create cache dir: " + cacheDir, e);
        }
        List<Double> keyframes = getCachedKeyframes(inputPath);
        String segmentTimes = buildSegmentTimes(keyframes);
        Path outputPattern = cacheDir.resolve(SEG_VIDEO_PREFIX + quality.getLabel() + "_%05d.ts");

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

        executePassWithTimeout(ffmpegFor(background)
                .addInput(buildInput(inputPath))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR),
                inputPath, "video pass " + quality.getLabel(),
                cacheDir, SEG_VIDEO_PREFIX + quality.getLabel() + "_");
        writeDoneMarker(cacheDir, SEG_VIDEO_PREFIX + quality.getLabel() + "_");
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
        startAudioPass(inputPath, cacheDir, streamIdx, audioQuality, sourceCodecName, false);
    }

    public void startAudioPass(String inputPath, Path cacheDir, int streamIdx, AudioQuality audioQuality,
                               String sourceCodecName, boolean background) {
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

        executePassWithTimeout(ffmpegFor(background)
                .addInput(UrlInput.fromUrl(inputPath))
                .addOutput(output)
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR),
                inputPath, "audio pass " + streamIdx + "/" + audioQuality.getLabel(),
                cacheDir, String.format("seg_audio_%d_%s_", streamIdx, audioQuality.getLabel()));
        writeDoneMarker(cacheDir, String.format("seg_audio_%d_%s_", streamIdx, audioQuality.getLabel()));
    }

    /**
     * Marks a pass as fully completed on disk. Presence of the marker (not the presence of
     * segments) tells later pre-transcode runs to skip the pass: a preempted or crashed pass
     * leaves segments behind but no marker, so it is correctly restarted.
     */
    private void writeDoneMarker(Path cacheDir, String segmentPrefix) {
        try {
            Files.writeString(cacheDir.resolve(DONE_MARKER_PREFIX + segmentPrefix), "");
        } catch (IOException e) {
            log.warn("Could not write done marker for {} in {}", segmentPrefix, cacheDir, e);
        }
    }

    /** True if a pass with this segment prefix ran to completion (marker present on disk). */
    public boolean hasDoneMarker(Path cacheDir, String segmentPrefix) {
        return Files.exists(cacheDir.resolve(DONE_MARKER_PREFIX + segmentPrefix));
    }

    // ========== Cache retention ==========

    /**
     * Extends the retention deadline of a media file's cache dir; the highest deadline ever
     * written wins. The cleanup job keeps the dir until the deadline has passed.
     */
    public void extendKeepUntil(UUID mediaFileId, long keepUntilEpochMillis) {
        Path dir = Paths.get(tmpDir, mediaFileId.toString());
        try {
            Files.createDirectories(dir);
            if (keepUntilEpochMillis > readKeepUntil(dir)) {
                Files.writeString(dir.resolve(KEEP_UNTIL_FILE), Long.toString(keepUntilEpochMillis));
            }
        } catch (IOException e) {
            log.warn("Could not write keep-until for {}", mediaFileId, e);
        }
    }

    /** Retention deadline (epoch millis) of a cache dir; 0 when absent or unreadable. */
    private long readKeepUntil(Path cacheDir) {
        Path file = cacheDir.resolve(KEEP_UNTIL_FILE);
        if (!Files.exists(file)) {
            return 0;
        }
        try {
            return Long.parseLong(Files.readString(file).trim());
        } catch (IOException | NumberFormatException e) {
            log.warn("Unreadable keep-until file {}", file, e);
            return 0;
        }
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
            pollUntilPassCompletes(future, overallDeadline, stallDeadline, timeoutSeconds, what, cacheDir, segmentPrefix);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("FFmpeg failed: " + what, e.getCause());
        } catch (InterruptedException e) {
            future.forceStop();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during FFmpeg run: " + what, e);
        }
    }

    private void pollUntilPassCompletes(com.github.kokorin.jaffree.ffmpeg.FFmpegResultFuture future,
                                        long overallDeadline, long stallDeadline, long timeoutSeconds,
                                        String what, Path cacheDir, String segmentPrefix)
            throws java.util.concurrent.ExecutionException, InterruptedException {
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

        deleteLegacyKeepFiles(root);
        Instant retentionThreshold = Instant.now().minus(cacheRetentionHours, ChronoUnit.HOURS);
        long now = System.currentTimeMillis();
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> cleanupCacheDir(dir, retentionThreshold, now));
        } catch (IOException e) {
            log.warn("Error during HLS cache cleanup", e);
        }
    }

    /** Removes a single HLS cache dir when it is stale and its retention deadline has passed. */
    private void cleanupCacheDir(Path dir, Instant retentionThreshold, long now) {
        String dirName = dir.getFileName().toString();
        if (NICE_WRAPPER_DIR_NAME.equals(dirName)) {
            return;
        }
        if (readKeepUntil(dir) > now) {
            log.debug("Skipping dir kept by retention deadline: {}", dir);
            return;
        }
        try (var entries = Files.list(dir)) {
            boolean allOld = entries.allMatch(f -> isOlderThan(f, retentionThreshold));
            if (allOld) {
                log.debug("Removing stale HLS cache: {}", dir);
                deleteRecursively(dir);
                forgetCacheDirState(dirName);
            }
        } catch (IOException e) {
            log.warn("Error scanning cache dir {}", dir, e);
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete {}", p, e);
                }
            });
        }
    }

    /** Drops all in-memory pass bookkeeping of a removed cache dir and frees its slot/budget. */
    private void forgetCacheDirState(String dirName) {
        activeGenerations.keySet().removeIf(k -> k.startsWith(dirName));
        generationLocks.keySet().removeIf(k -> k.startsWith(dirName));
        knownStableSegments.removeIf(k -> k.contains(dirName));
        segmentSizeSamples.keySet().removeIf(k -> k.contains(dirName));
        if (filesWithAcquiredSlot.remove(dirName)) {
            concurrentFileSlots.release();
            log.warn("Released stale transcode slot during cleanup: {}", dirName);
        }
        if (filesWithBackgroundBudget.remove(dirName)) {
            backgroundFileBudget.release();
        }
        preemptedFiles.remove(dirName);
        activePassesPerFile.remove(dirName);
    }

    /** Removes the keep files of the pre-retention-deadline era; superseded by {@link #KEEP_UNTIL_FILE}. */
    private void deleteLegacyKeepFiles(Path root) {
        try (var files = Files.list(root)) {
            files.filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().startsWith("pretranscode_keep_")
                              && p.getFileName().toString().endsWith(".txt"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            log.info("Deleted legacy pre-transcode keep file {}", p);
                        } catch (IOException e) {
                            log.warn("Could not delete legacy keep file {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not scan for legacy keep files", e);
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
