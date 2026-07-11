package app.ister.transcoder;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.eventdata.TranscodePassRequestedData;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.service.MessageSender;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * HLS service — coordinates playlist building, transcoding, and subtitle handling.
 * <p>
 * Playlist generation is triggered by a {@code TRANSCODE_REQUESTED} RabbitMQ event.
 * Individual FFmpeg passes are started lazily: the first {@code .ts} segment request for a
 * quality level sends a {@code TRANSCODE_PASS_REQUESTED} event; the pass handler calls
 * {@link #startPass} which delegates to {@link HlsTranscodeService#ensurePassStarted}.
 * All generated files are cached under {@code tmpDir/{mediaFileId}/}.
 */
@Service
@Slf4j
public class HlsService {

    private static final String EXT_M3U8 = ".m3u8";
    private static final String EXT_X_MEDIA = "#EXT-X-MEDIA";
    private static final String EXT_X_STREAM_INF = "#EXT-X-STREAM-INF";
    private static final String PASS_CATEGORY_VIDEO = "video";
    private static final String PASS_CATEGORY_AUDIO = "audio";

    private final HlsPlaylistBuilder playlistBuilder;
    private final HlsSubtitleService subtitleService;
    private final HlsTranscodeService transcodeService;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final MessageSender messageSender;
    private final RemoteNodeClient remoteNodeClient;
    private final NodeTokenManager nodeTokenManager;

    /**
     * Short read-only transactions for the HTTP request paths. The HLS endpoints poll for
     * files for up to two minutes; an @Transactional spanning such a method pins a Hikari
     * connection for the whole wait, which exhausted the pool under a handful of concurrent
     * players. All entity reads happen inside this template and return plain values; the
     * RabbitMQ sends, ffmpeg/ffprobe work and poll loops run without an active transaction.
     */
    private final TransactionTemplate readOnlyTransaction;

    @SuppressWarnings("java:S107") // wiring, one collaborator per concern
    public HlsService(HlsPlaylistBuilder playlistBuilder, HlsSubtitleService subtitleService,
                      HlsTranscodeService transcodeService, MediaFileRepository mediaFileRepository,
                      MediaFileStreamRepository mediaFileStreamRepository, MessageSender messageSender,
                      RemoteNodeClient remoteNodeClient, NodeTokenManager nodeTokenManager,
                      PlatformTransactionManager transactionManager) {
        this.playlistBuilder = playlistBuilder;
        this.subtitleService = subtitleService;
        this.transcodeService = transcodeService;
        this.mediaFileRepository = mediaFileRepository;
        this.mediaFileStreamRepository = mediaFileStreamRepository;
        this.messageSender = messageSender;
        this.remoteNodeClient = remoteNodeClient;
        this.nodeTokenManager = nodeTokenManager;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @Value("${app.ister.server.hls.master-playlist-timeout-ms:120000}")
    private long masterPlaylistTimeoutMs;

    /** After the pass completes, keep retrying failed segment uploads for at most this long. */
    @Value("${app.ister.transcoder.hls.upload-drain-timeout-ms:300000}")
    private long uploadDrainTimeoutMs;

    @Value("${app.ister.server.name}")
    private String localNodeName;

    /** Per-subtitle locks to prevent duplicate segment generation for the same subtitle stream. */
    private final ConcurrentHashMap<String, Object> subtitleLocks = new ConcurrentHashMap<>();

    /** Daemon thread pool for segment-upload watcher threads (remote transcoding). */
    private final ExecutorService watcherExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "segment-watcher");
        t.setDaemon(true);
        return t;
    });

    // ========== Public API ==========

    /**
     * Returns (cached) master.m3u8 content.
     * On cache miss, sends a {@code TRANSCODE_REQUESTED} RabbitMQ event and polls until
     * the event handler has written the file to cache.
     *
     * @param direct    include the stream-copy (direct) video + audio-copy quality variant
     * @param transcode include the re-encoded (720p + 480p) video quality variants
     */
    public String getMasterPlaylist(UUID mediaFileId, boolean direct, boolean transcode, SubtitleFormat subtitleFormat) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(masterCacheFilename(direct, transcode, subtitleFormat));

        if (Files.exists(cacheFile)) {
            String cached = Files.readString(cacheFile);
            if (cached.contains(EXT_X_MEDIA) || cached.contains(EXT_X_STREAM_INF)) {
                Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
                return cached;
            }
            log.warn("Stale master playlist cache for {} has no stream entries, deleting and regenerating", mediaFileId);
            Files.delete(cacheFile);
        }

        String directoryName = readOnlyTransaction.execute(status -> {
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            if (mediaFile.getMediaFileStreamEntity() == null || mediaFile.getMediaFileStreamEntity().isEmpty()) {
                return null;
            }
            return mediaFile.getDirectoryEntity().getName();
        });
        if (directoryName == null) {
            throw new IOException("Media file not yet analyzed, no stream entries for: " + mediaFileId);
        }
        log.debug("Master playlist cache miss for {}, sending TRANSCODE_REQUESTED to directory queue {}", mediaFileId, directoryName);

        TranscodeRequestedData request = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(mediaFileId)
                .direct(direct)
                .transcode(transcode)
                .subtitleFormat(subtitleFormat)
                .build();
        messageSender.sendTranscodeRequested(request, directoryName);

        try {
            return waitForMasterPlaylist(cacheFile, masterPlaylistTimeoutMs / 2);
        } catch (IOException _) {
            // The event may have been lost or dead-lettered; re-issue it once before giving up.
            log.warn("Master playlist for {} not produced in time, re-sending TRANSCODE_REQUESTED once", mediaFileId);
            messageSender.sendTranscodeRequested(request, directoryName);
            return waitForMasterPlaylist(cacheFile, masterPlaylistTimeoutMs / 2);
        }
    }

    /**
     * Generates master.m3u8 and all stream playlists and writes them to cache.
     * Called by the {@code HandleTranscodeRequested} event handler.
     * If the media file is on a remote node, all generated playlists are uploaded to that node.
     * <p>
     * Deliberately NOT @Transactional: the keyframe ffprobe can scan a whole video over the
     * mount for minutes, and a transaction spanning it pins a Hikari connection per busy
     * listener thread. The entity (with its streams initialized) is loaded in a short
     * transaction; everything after runs detached and only reads basic fields.
     */
    public void generateAllPlaylists(UUID mediaFileId, boolean direct, boolean transcode, SubtitleFormat subtitleFormat) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(masterCacheFilename(direct, transcode, subtitleFormat));

        // Duplicate TRANSCODE_REQUESTED events are common (poll-timeout re-sends,
        // scan-time pre-generation); keep that path DB-free.
        if (Files.exists(cacheFile)) {
            log.debug("Playlists already cached for {}, skipping generation", mediaFileId);
            return;
        }

        MediaFileEntity mediaFile = readOnlyTransaction.execute(status -> {
            MediaFileEntity entity = mediaFileRepository.findById(mediaFileId).orElseThrow();
            // Initialize every association the detached path navigates while the session is
            // open; the RabbitMQ listener thread has no OSIV. Bytecode enhancement makes even
            // EAGER-annotated to-ones throw outside the session, so the directory→node chain
            // (isRemote/resolveInputPath) needs explicit touching just like the streams.
            Hibernate.initialize(entity.getMediaFileStreamEntity());
            if (entity.getDirectoryEntity() != null) {
                entity.getDirectoryEntity().getNodeEntity().getUrl();
            }
            return entity;
        });

        Files.createDirectories(cacheFile.getParent());
        String masterContent = playlistBuilder.buildMasterPlaylist(mediaFile, direct, transcode, subtitleFormat);
        if (!masterContent.contains(EXT_X_MEDIA) && !masterContent.contains(EXT_X_STREAM_INF)) {
            log.warn("Master playlist for {} has no stream entries (streams not yet analyzed?), skipping cache write", mediaFileId);
            return;
        }
        preGenerateStreamPlaylists(mediaFile, mediaFileId, direct, transcode, subtitleFormat);
        // Write master playlist last so that getMasterPlaylist's poll only fires after all stream playlists exist
        Files.writeString(cacheFile, masterContent);
        writeAllMasterVariantsForAudioOnly(mediaFile, mediaFileId);
        log.debug("Generated all playlists for {}", mediaFileId);

        if (isRemote(mediaFile)) {
            String nodeUrl = mediaFile.getDirectoryEntity().getNodeEntity().getUrl();
            try (Stream<Path> files = Files.list(cacheDir(mediaFileId))) {
                files.filter(p -> p.toString().endsWith(EXT_M3U8))
                        .forEach(p -> {
                            try {
                                remoteNodeClient.uploadFile(nodeUrl, mediaFileId, p);
                            } catch (IOException e) {
                                log.warn("Playlist upload failed: {}", p, e);
                            }
                        });
            }
        }
    }

    /**
     * Starts the FFmpeg pass described by the given event data.
     * Called by the {@code HandleTranscodePassRequested} event handler.
     * For remote media files, a watcher thread uploads each stable segment to the source node.
     */
    @Transactional(readOnly = true)
    public void startPass(TranscodePassRequestedData data) {
        MediaFileEntity mediaFile = mediaFileRepository.findById(data.getMediaFileId()).orElseThrow();
        doStartPass(data, mediaFile);
    }

    private void doStartPass(TranscodePassRequestedData data, MediaFileEntity mediaFile) {
        boolean remote = isRemote(mediaFile);
        boolean background = Boolean.TRUE.equals(data.getBackground());
        String mediaFilePath = resolveInputPath(mediaFile);

        Path cacheDirPath = cacheDir(data.getMediaFileId());
        String segmentPrefix;
        Runnable passStarter;

        if (PASS_CATEGORY_VIDEO.equals(data.getPassCategory())) {
            VideoQuality vq = VideoQuality.fromLabel(data.getQualityLabel());
            segmentPrefix = "seg_video_" + data.getQualityLabel() + "_";
            passStarter = () -> transcodeService.startVideoPass(mediaFilePath, cacheDirPath, vq, background);
        } else {
            AudioQuality aq = AudioQuality.fromLabel(data.getQualityLabel());
            int audioStreamIndex = data.getAudioStreamIndex();
            segmentPrefix = "seg_audio_" + audioStreamIndex + "_" + data.getQualityLabel() + "_";
            String sourceCodec = mediaFile.getMediaFileStreamEntity().stream()
                    .filter(s -> s.getStreamIndex() == audioStreamIndex)
                    .map(MediaFileStreamEntity::getCodecName)
                    .findFirst()
                    .orElse("");
            passStarter = () -> transcodeService.startAudioPass(mediaFilePath, cacheDirPath,
                    audioStreamIndex, aq, sourceCodec, background);
        }

        transcodeService.ensurePassStarted(data.getPassKey(), passStarter, background);

        if (remote) {
            String nodeUrl = mediaFile.getDirectoryEntity().getNodeEntity().getUrl();
            UUID mediaFileId = data.getMediaFileId();
            CompletableFuture<Void> passFuture = transcodeService.getActiveFuture(data.getPassKey());
            watcherExecutor.submit(() ->
                    watchAndUpload(cacheDirPath, segmentPrefix, nodeUrl, mediaFileId, passFuture));
        }
    }

    /**
     * Starts FFmpeg passes for all quality variants of the given media file.
     * Called after playlist generation so that .ts segments are produced eagerly.
     * Already-running or completed passes are skipped.
     *
     * @param direct    whether the stream-copy (direct) quality is included
     * @param transcode whether the re-encoded (720p + 480p) qualities are included
     */
    @Transactional(readOnly = true)
    public void startAllPasses(UUID mediaFileId, boolean direct, boolean transcode) {
        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
        String inputPath = resolveInputPath(mediaFile);

        boolean[] includeQuality = {direct, transcode, transcode};
        VideoQuality[] videoQualities = VideoQuality.values();
        AudioQuality[] audioQualities = AudioQuality.values();

        List<MediaFileStreamEntity> audioStreams = mediaFile.getMediaFileStreamEntity().stream()
                .filter(s -> s.getCodecType() == StreamCodecType.AUDIO)
                .toList();

        // Never start video passes for a file without a real video stream: FFmpeg's
        // -map 0:v:0 would grab embedded cover art and fail (or transcode a JPEG).
        boolean hasVideo = mediaFile.getMediaFileStreamEntity().stream()
                .anyMatch(HlsPlaylistBuilder::isRealVideoStream);
        for (int i = 0; hasVideo && i < videoQualities.length; i++) {
            if (includeQuality[i]) {
                startVideoPassIfNeeded(mediaFileId, inputPath, videoQualities[i], mediaFile);
            }
        }

        for (int qi = 0; qi < audioQualities.length; qi++) {
            if (!includeQuality[qi] || audioQualities[qi] == AudioQuality.COPY) continue;
            String qualityLabel = audioQualities[qi].getLabel();
            for (MediaFileStreamEntity audioStream : audioStreams) {
                startAudioPassIfNeeded(mediaFileId, inputPath, qualityLabel, audioStream.getStreamIndex(), mediaFile);
            }
        }
    }

    private void startVideoPassIfNeeded(UUID mediaFileId, String inputPath, VideoQuality vq, MediaFileEntity mediaFile) {
        String qualityLabel = vq.getLabel();
        String passKey = mediaFileId + "_video_" + qualityLabel;
        if (transcodeService.isPassActive(passKey) || transcodeService.hasCompletedPass(passKey)) return;
        if (transcodeService.hasDoneMarker(cacheDir(mediaFileId), "seg_video_" + qualityLabel + "_")) {
            log.debug("Skipping video pass for {} quality={} — pass already completed on disk", mediaFileId, qualityLabel);
            return;
        }
        doStartPass(TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .mediaFileId(mediaFileId)
                .passKey(passKey)
                .mediaFilePath(inputPath)
                .passCategory(PASS_CATEGORY_VIDEO)
                .qualityLabel(qualityLabel)
                .background(true)
                .build(), mediaFile);
    }

    private void startAudioPassIfNeeded(UUID mediaFileId, String inputPath, String qualityLabel,
                                         int streamIdx, MediaFileEntity mediaFile) {
        String passKey = mediaFileId + "_audio_" + streamIdx + "_" + qualityLabel;
        if (transcodeService.isPassActive(passKey) || transcodeService.hasCompletedPass(passKey)) return;
        if (transcodeService.hasDoneMarker(cacheDir(mediaFileId), "seg_audio_" + streamIdx + "_" + qualityLabel + "_")) {
            log.debug("Skipping audio pass for {} streamIdx={} quality={} — pass already completed on disk", mediaFileId, streamIdx, qualityLabel);
            return;
        }
        doStartPass(TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .mediaFileId(mediaFileId)
                .passKey(passKey)
                .mediaFilePath(inputPath)
                .passCategory(PASS_CATEGORY_AUDIO)
                .qualityLabel(qualityLabel)
                .audioStreamIndex(streamIdx)
                .background(true)
                .build(), mediaFile);
    }

    /**
     * Returns (cached) stream playlist content.
     * Filename determines the type: stream_video_*, stream_audio_*, or stream_sub_*.
     */
    public String getStreamPlaylist(UUID mediaFileId, String streamFilename) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(streamFilename);
        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readString(cacheFile);
        }
        StreamPlaylistContext ctx = readOnlyTransaction.execute(status -> {
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            return new StreamPlaylistContext(resolveInputPath(mediaFile), isAudioOnly(mediaFile),
                    mediaFile.getDurationInMilliseconds());
        });
        seedProbeCachesForAudioOnly(ctx);
        List<Double> keyframes = transcodeService.getCachedKeyframes(ctx.filePath());
        double totalDuration = transcodeService.getTotalDuration(ctx.filePath());
        if (keyframes.isEmpty() && ctx.audioOnly()) {
            // Audio-only files are seeded with an empty keyframe list; mirror the synthetic
            // 10s grid generateAllPlaylists uses (the audio pass falls back to
            // -segment_time 10), or buildVodPlaylist throws. Video keeps throwing: its
            // pass has no such fallback, so a synthetic playlist would not match.
            keyframes = buildSyntheticKeyframes(totalDuration);
        }
        Files.createDirectories(cacheFile.getParent());
        String content = playlistBuilder.buildStreamPlaylist(streamFilename, keyframes, totalDuration);
        Files.writeString(cacheFile, content);
        return content;
    }

    /**
     * Returns path to (cached) video-only .ts segment.
     * <p>
     * On first request for a quality level, sends a {@code TRANSCODE_PASS_REQUESTED} event
     * to start the background FFmpeg pass, then polls until the segment appears.
     */
    public Path getVideoSegment(UUID mediaFileId, String segmentFilename) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(segmentFilename);

        Path stable = transcodeService.stableSegmentOrNull(cacheFile);
        if (stable != null) return stable;
        Files.createDirectories(cacheFile.getParent());
        String[] parts = segmentFilename.replace(".ts", "").split("_");
        String qualityLabel = parts[2];
        String passKey = mediaFileId + "_video_" + qualityLabel;
        Path completed = completedSegmentOrNull(mediaFileId, cacheFile, "seg_video_" + qualityLabel + "_");
        if (completed != null) return completed;
        requestPassIfNeeded(mediaFileId, passKey, PASS_CATEGORY_VIDEO, qualityLabel, null);
        return transcodeService.waitForSegment(cacheFile, passKey);
    }

    /**
     * Returns path to (cached) audio-only .ts segment.
     * <p>
     * COPY format:       {@code seg_audio_{start}_{duration}_{streamIdx}_copy.ts} — generated on demand per segment.
     * Transcoded format: {@code seg_audio_{streamIdx}_{bitrate}_%05d.ts}           — produced by a background FFmpeg pass.
     * On first request for a transcoded quality, sends a {@code TRANSCODE_PASS_REQUESTED} event.
     */
    public Path getAudioSegment(UUID mediaFileId, String segmentFilename) throws IOException {
        String[] parts = segmentFilename.replace(".ts", "").split("_");
        Path cacheFile = cacheDir(mediaFileId).resolve(segmentFilename);

        if ("copy".equals(parts[parts.length - 1])) {
            if (Files.exists(cacheFile)) {
                // Cache hits stay DB-free: repeat segment requests are the common case
                // and must not compete for pool connections during playback.
                Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
                return cacheFile;
            }
            int audioIdx = Integer.parseInt(parts[4]);
            // Resolve everything from the DB up front: the generator below runs a synchronous
            // FFmpeg invocation and must not hold entities or a connection while it does.
            CopyAudioContext ctx = readOnlyTransaction.execute(status -> {
                MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
                String codecName = mediaFile.getMediaFileStreamEntity().stream()
                        .filter(s -> s.getStreamIndex() == audioIdx)
                        .map(MediaFileStreamEntity::getCodecName)
                        .findFirst()
                        .orElse("aac");
                return new CopyAudioContext(resolveInputPath(mediaFile), codecName);
            });
            return getCachedOrGenerateBinary(cacheFile, out -> {
                double start = Double.parseDouble(parts[2]);
                double duration = Double.parseDouble(parts[3]);
                transcodeService.generateAudioSegment(ctx.filePath(), out, start, duration, audioIdx, AudioQuality.COPY, ctx.codecName());
            });
        }

        // Transcoded: seg_audio_{streamIdx}_{bitrate}_{%05d}.ts
        Path stable = transcodeService.stableSegmentOrNull(cacheFile);
        if (stable != null) return stable;
        Files.createDirectories(cacheFile.getParent());
        int streamIdx = Integer.parseInt(parts[2]);
        String bitrateLabel = parts[3];
        String passKey = mediaFileId + "_audio_" + streamIdx + "_" + bitrateLabel;
        Path completed = completedSegmentOrNull(mediaFileId, cacheFile, "seg_audio_" + streamIdx + "_" + bitrateLabel + "_");
        if (completed != null) return completed;
        requestPassIfNeeded(mediaFileId, passKey, PASS_CATEGORY_AUDIO, bitrateLabel, streamIdx);
        return transcodeService.waitForSegment(cacheFile, passKey);
    }

    /**
     * Fast cache-hit for a fully pre-transcoded pass. When a pass wrote its done marker
     * (see {@link HlsTranscodeService#writeDoneMarker}) every segment it produced is final, so
     * the requested segment can be served straight from disk — without waiting on the
     * size-stability window, and crucially without re-triggering a whole-file pass because the
     * in-memory pass record is gone (e.g. after a restart, or once the completed future was
     * evicted). Without this, playback ignores the done marker the pre-transcoder wrote and
     * needlessly re-encodes a file that is already complete on disk.
     *
     * @return the segment path when its pass completed on disk and the file is present; else {@code null}
     */
    private Path completedSegmentOrNull(UUID mediaFileId, Path cacheFile, String segmentPrefix) throws IOException {
        if (!transcodeService.hasDoneMarker(cacheDir(mediaFileId), segmentPrefix)) {
            return null;
        }
        if (Files.exists(cacheFile) && Files.size(cacheFile) > 0) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return cacheFile;
        }
        return null;
    }

    /**
     * Sends a {@code TRANSCODE_PASS_REQUESTED} event for the given pass unless a pass with this
     * key is already active, completed, or failed. The media file is only loaded when an event
     * actually needs to be sent.
     *
     * @param audioStreamIndex audio stream index, or {@code null} for video passes
     */
    private void requestPassIfNeeded(UUID mediaFileId, String passKey, String passCategory,
                                     String qualityLabel, Integer audioStreamIndex) {
        if (transcodeService.isPassActive(passKey) || transcodeService.hasCompletedPass(passKey)
                || transcodeService.hasFailedPass(passKey)) {
            return;
        }
        PassRequestContext ctx = readOnlyTransaction.execute(status -> {
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            return new PassRequestContext(resolveInputPath(mediaFile), mediaFile.getDirectoryEntity().getName());
        });
        messageSender.sendTranscodePassRequested(
                TranscodePassRequestedData.builder()
                        .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                        .mediaFileId(mediaFileId)
                        .passKey(passKey)
                        .mediaFilePath(ctx.inputPath())
                        .passCategory(passCategory)
                        .qualityLabel(qualityLabel)
                        .audioStreamIndex(audioStreamIndex)
                        .build(),
                ctx.directoryName());
    }

    /**
     * Returns (cached) WebVTT subtitle segment content.
     * Filename format: {@code seg_sub_{subtitleStreamEntityId}_{%05d}.vtt}
     */
    public String getSubtitleSegment(UUID mediaFileId, String segmentFilename) throws IOException {
        // Parse: seg_sub_{uuid}_{%05d}.vtt
        String withoutExt = segmentFilename.replace(".vtt", "");
        int lastUnderscore = withoutExt.lastIndexOf('_');
        UUID subtitleId = UUID.fromString(withoutExt.substring("seg_sub_".length(), lastUnderscore));

        Path cacheFile = cacheDir(mediaFileId).resolve(segmentFilename);
        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readString(cacheFile, StandardCharsets.UTF_8);
        }
        Files.createDirectories(cacheFile.getParent());

        // The subtitle stream entity is fully loaded here; generation below only reads its
        // basic fields (id, codecType, path, streamIndex), never lazy associations.
        SubtitleContext ctx = readOnlyTransaction.execute(status -> {
            MediaFileStreamEntity subtitleStream = mediaFileStreamRepository.findById(subtitleId).orElseThrow();
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            return new SubtitleContext(subtitleStream, resolveInputPath(mediaFile));
        });
        MediaFileStreamEntity subtitleStream = ctx.subtitleStream();
        String mediaFilePath = ctx.mediaFilePath();
        String generationKey = mediaFileId + "_sub_" + subtitleId;
        Object lock = subtitleLocks.computeIfAbsent(generationKey, k -> new Object());
        synchronized (lock) {
            if (!Files.exists(cacheFile)) {
                subtitleService.generateSubtitleSegments(subtitleStream, mediaFilePath, mediaFileId, cacheDir(mediaFileId));
            }
        }
        return Files.readString(cacheFile, StandardCharsets.UTF_8);
    }

    /**
     * Returns path to (cached) SRT subtitle file.
     * Filename format: {@code sub_{subtitleStreamEntityId}.srt}
     * <p>
     * For external subtitles the original file is returned directly.
     * For embedded subtitles the stream is extracted to SRT and cached.
     */
    public Path getSrtSubtitle(UUID mediaFileId, String filename) throws IOException {
        UUID subtitleId = UUID.fromString(filename.replace("sub_", "").replace(".srt", ""));
        SubtitleContext ctx = readOnlyTransaction.execute(status -> {
            MediaFileStreamEntity stream = mediaFileStreamRepository.findById(subtitleId).orElseThrow();
            String mediaFilePath = stream.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE ? null
                    : resolveInputPath(mediaFileRepository.findById(mediaFileId).orElseThrow());
            return new SubtitleContext(stream, mediaFilePath);
        });
        MediaFileStreamEntity stream = ctx.subtitleStream();

        String sourceSrtPath;
        if (stream.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE) {
            sourceSrtPath = stream.getPath();
        } else {
            Files.createDirectories(cacheDir(mediaFileId));
            sourceSrtPath = subtitleService.extractEmbeddedSubtitleToSrt(stream, ctx.mediaFilePath(), cacheDir(mediaFileId));
        }

        Path offsetPath = cacheDir(mediaFileId).resolve("sub_" + subtitleId + "_offset.srt");
        subtitleService.writeSrtWithOffset(sourceSrtPath, offsetPath);
        return offsetPath;
    }

    // ========== Stream playlist pre-generation ==========

    private void preGenerateStreamPlaylists(MediaFileEntity mediaFile, UUID mediaFileId,
                                             boolean direct, boolean transcode,
                                             SubtitleFormat subtitleFormat) throws IOException {
        String filePath = resolveInputPath(mediaFile);
        boolean audioOnly = isAudioOnly(mediaFile);
        seedProbeCachesForAudioOnly(new StreamPlaylistContext(filePath, audioOnly,
                mediaFile.getDurationInMilliseconds()));
        List<Double> rawKeyframes = transcodeService.getCachedKeyframes(filePath);
        double totalDuration = transcodeService.getTotalDuration(filePath);

        List<MediaFileStreamEntity> streams = mediaFile.getMediaFileStreamEntity();
        List<MediaFileStreamEntity> audioStreams = streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.AUDIO)
                .toList();
        List<MediaFileStreamEntity> subtitleStreams = streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE
                        || s.getCodecType() == StreamCodecType.SUBTITLE)
                .toList();

        // Audio-only files get every stream playlist regardless of the requested stream
        // settings: they are a few hundred bytes each, and it makes every master variant
        // (see writeAllMasterVariantsForAudioOnly) a complete cache hit.
        boolean[] includeVideo = audioOnly
                ? new boolean[]{true, true, true}
                : new boolean[]{direct, transcode, transcode};
        VideoQuality[] videoQualities = VideoQuality.values();
        AudioQuality[] audioQualities = AudioQuality.values();

        boolean hasVideoStream = streams.stream().anyMatch(HlsPlaylistBuilder::isRealVideoStream);
        List<Double> effectiveKeyframes = rawKeyframes.isEmpty() ? buildSyntheticKeyframes(totalDuration) : rawKeyframes;
        if (rawKeyframes.isEmpty() && !audioOnly) {
            log.warn("No keyframes found for {}, falling back to synthetic keyframes", filePath);
        }

        if (hasVideoStream) {
            for (int i = 0; i < videoQualities.length; i++) {
                if (!includeVideo[i]) continue;
                VideoQuality vq = videoQualities[i];
                String filename = "stream_video_" + vq.getLabel() + EXT_M3U8;
                String qualityLabel = vq.getLabel();
                writeStreamPlaylistIfAbsent(mediaFileId, filename, effectiveKeyframes, totalDuration,
                        (start, dur, idx) -> String.format(Locale.ROOT, "seg_video_%s_%05d.ts", qualityLabel, idx));
            }
        }

        preGenerateAudioPlaylists(mediaFileId, audioStreams, audioQualities, includeVideo, effectiveKeyframes, totalDuration);
        preGenerateSubtitlePlaylists(mediaFileId, subtitleStreams, subtitleFormat, effectiveKeyframes, totalDuration);
    }

    private void preGenerateAudioPlaylists(UUID mediaFileId, List<MediaFileStreamEntity> audioStreams,
                                            AudioQuality[] audioQualities, boolean[] includeVideo,
                                            List<Double> keyframes, double totalDuration) throws IOException {
        for (int qi = 0; qi < audioQualities.length; qi++) {
            if (!includeVideo[qi]) continue;
            AudioQuality aq = audioQualities[qi];
            for (MediaFileStreamEntity as : audioStreams) {
                String filename = String.format(Locale.ROOT, "stream_audio_%d_%s" + EXT_M3U8, as.getStreamIndex(), aq.getLabel());
                if (aq == AudioQuality.COPY) {
                    writePlaylistIfAbsent(mediaFileId, filename,
                            playlistBuilder.buildSingleSegmentPlaylist(totalDuration,
                                    String.format(Locale.ROOT, "seg_audio_0.000000_%.6f_%d_copy.ts", totalDuration, as.getStreamIndex())));
                } else {
                    int streamIndex = as.getStreamIndex();
                    String bitrateLabel = aq.getLabel();
                    writeStreamPlaylistIfAbsent(mediaFileId, filename, keyframes, totalDuration,
                            (start, dur, idx) -> String.format(Locale.ROOT, "seg_audio_%d_%s_%05d.ts", streamIndex, bitrateLabel, idx));
                }
            }
        }
    }

    private void preGenerateSubtitlePlaylists(UUID mediaFileId, List<MediaFileStreamEntity> subtitleStreams,
                                               SubtitleFormat subtitleFormat,
                                               List<Double> keyframes, double totalDuration) throws IOException {
        String formatLabel = subtitleFormat.name().toLowerCase();
        for (MediaFileStreamEntity ss : subtitleStreams) {
            String filename = "stream_sub_" + ss.getId() + "_" + formatLabel + EXT_M3U8;
            if (subtitleFormat == SubtitleFormat.SRT) {
                writePlaylistIfAbsent(mediaFileId, filename,
                        playlistBuilder.buildSingleSegmentPlaylist(totalDuration, "sub_" + ss.getId() + ".srt"));
            } else {
                UUID ssId = ss.getId();
                writeStreamPlaylistIfAbsent(mediaFileId, filename, keyframes, totalDuration,
                        (start, dur, idx) -> String.format(Locale.ROOT, "seg_sub_%s_%05d.vtt", ssId, idx));
            }
        }
    }

    /**
     * For audio-only files, also writes the master playlists for every other stream-settings
     * combination the client can request. All stream playlists exist for audio-only files
     * (see preGenerateStreamPlaylists), so each variant is a handful of string writes and any
     * later stream-settings choice is an instant cache hit instead of a queue round-trip.
     */
    private void writeAllMasterVariantsForAudioOnly(MediaFileEntity mediaFile, UUID mediaFileId) throws IOException {
        if (!isAudioOnly(mediaFile)) {
            return;
        }
        boolean[][] directTranscodeCombos = {{true, false}, {false, true}, {true, true}};
        for (boolean[] combo : directTranscodeCombos) {
            for (SubtitleFormat format : SubtitleFormat.values()) {
                Path variantFile = cacheDir(mediaFileId).resolve(masterCacheFilename(combo[0], combo[1], format));
                if (Files.exists(variantFile)) {
                    continue;
                }
                String content = playlistBuilder.buildMasterPlaylist(mediaFile, combo[0], combo[1], format);
                if (content.contains(EXT_X_MEDIA) || content.contains(EXT_X_STREAM_INF)) {
                    Files.writeString(variantFile, content);
                }
            }
        }
    }

    /**
     * A file counts as audio-only when it has been analyzed (stream rows exist) and none of
     * the streams is video. An un-analyzed file (no stream rows) is NOT audio-only: its
     * probes must still run.
     */
    private static boolean isAudioOnly(MediaFileEntity mediaFile) {
        List<MediaFileStreamEntity> streams = mediaFile.getMediaFileStreamEntity();
        return streams != null && !streams.isEmpty()
                && streams.stream().noneMatch(HlsPlaylistBuilder::isRealVideoStream);
    }

    /**
     * Audio-only files need no ffprobe at all: the keyframe probe scans the whole file over
     * the mount only to find no video packets, and the duration is already in the database
     * from analysis. Seeding both caches here also keeps the later FFmpeg passes
     * (startAudioPass reads the same caches) probe-free.
     */
    private void seedProbeCachesForAudioOnly(StreamPlaylistContext ctx) {
        if (!ctx.audioOnly()) {
            return;
        }
        transcodeService.seedAudioOnlyKeyframes(ctx.filePath());
        if (ctx.durationInMilliseconds() > 0) {
            transcodeService.seedDuration(ctx.filePath(), ctx.durationInMilliseconds() / 1000.0);
        }
    }

    private List<Double> buildSyntheticKeyframes(double totalDuration) {
        double interval = 10.0;
        List<Double> keyframes = new ArrayList<>();
        for (double t = 0; t < totalDuration; t += interval) {
            keyframes.add(t);
        }
        return keyframes;
    }

    private void writeStreamPlaylistIfAbsent(UUID mediaFileId, String filename,
                                              List<Double> keyframes, double totalDuration,
                                              HlsPlaylistBuilder.SegmentNamer namer) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(filename);
        if (!Files.exists(cacheFile)) {
            Files.writeString(cacheFile, playlistBuilder.buildVodPlaylist(keyframes, totalDuration, namer));
        }
    }

    private void writePlaylistIfAbsent(UUID mediaFileId, String filename, String content) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(filename);
        if (!Files.exists(cacheFile)) {
            Files.writeString(cacheFile, content);
        }
    }

    // ========== Remote node helpers ==========

    private boolean isRemote(MediaFileEntity mediaFile) {
        return !localNodeName.equals(mediaFile.getDirectoryEntity().getNodeEntity().getName());
    }

    private String resolveInputPath(MediaFileEntity mediaFile) {
        if (!isRemote(mediaFile)) {
            return mediaFile.getPath();
        }
        return mediaFile.getDirectoryEntity().getNodeEntity().getUrl()
                + "/mediaFile/" + mediaFile.getId()
                + "/download?token=" + nodeTokenManager.getDownloadToken();
    }

    private void watchAndUpload(Path cacheDirPath, String prefix, String nodeUrl,
                                 UUID mediaFileId, CompletableFuture<Void> passFuture) {
        Set<String> uploaded = new HashSet<>();
        long drainDeadline = -1;
        boolean running = true;
        while (running && (!passFuture.isDone() || !allUploaded(cacheDirPath, prefix, uploaded))) {
            if (passFuture.isDone()) {
                // Pass finished but uploads are incomplete (e.g. peer unreachable): keep
                // retrying for a bounded drain window instead of looping forever.
                if (drainDeadline < 0) {
                    drainDeadline = System.currentTimeMillis() + uploadDrainTimeoutMs;
                } else if (System.currentTimeMillis() > drainDeadline) {
                    log.warn("Giving up uploading remaining segments for {} after {} ms", cacheDirPath, uploadDrainTimeoutMs);
                    running = false;
                }
            }
            if (running && !scanAndUploadBatch(cacheDirPath, prefix, nodeUrl, mediaFileId, uploaded)) {
                running = false;
            }
        }
    }

    private boolean scanAndUploadBatch(Path cacheDirPath, String prefix, String nodeUrl,
                                        UUID mediaFileId, Set<String> uploaded) {
        try (Stream<Path> files = Files.list(cacheDirPath)) {
            files.filter(p -> p.getFileName().toString().startsWith(prefix)
                           && p.getFileName().toString().endsWith(".ts")
                           && !uploaded.contains(p.getFileName().toString()))
                 .forEach(p -> tryUploadSegment(nodeUrl, mediaFileId, p, uploaded));
            Thread.sleep(500);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.nio.file.NoSuchFileException _) {
            log.debug("Cache dir removed, stopping watcher: {}", cacheDirPath);
            return false;
        } catch (IOException e) {
            log.warn("Watcher error in {}", cacheDirPath, e);
            return false;
        }
    }

    private void tryUploadSegment(String nodeUrl, UUID mediaFileId, Path p, Set<String> uploaded) {
        try {
            if (transcodeService.stableSegmentOrNull(p) != null) {
                remoteNodeClient.uploadFile(nodeUrl, mediaFileId, p);
                uploaded.add(p.getFileName().toString());
            }
        } catch (IOException e) {
            log.warn("Segment upload failed: {}", p, e);
        }
    }

    private boolean allUploaded(Path cacheDir, String prefix, Set<String> uploaded) {
        try (Stream<Path> files = Files.list(cacheDir)) {
            List<String> all = files
                    .filter(p -> p.getFileName().toString().startsWith(prefix)
                              && p.getFileName().toString().endsWith(".ts"))
                    .map(p -> p.getFileName().toString())
                    .toList();
            return !all.isEmpty() && uploaded.containsAll(all);
        } catch (IOException _) {
            return false;
        }
    }

    // ========== Polling ==========

    private String waitForMasterPlaylist(Path cacheFile, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(cacheFile)) {
                String content = Files.readString(cacheFile);
                // Guard against reading the file between creation and the write completing
                if (!content.isBlank()) {
                    return content;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for master playlist");
            }
        }
        throw new IOException("Timeout waiting for master playlist: " + cacheFile);
    }

    // ========== Cache helpers ==========

    private Path cacheDir(UUID mediaFileId) {
        return Paths.get(tmpDir, mediaFileId.toString());
    }

    /** Single source of the master-playlist cache key: getMasterPlaylist polls for the
     * exact filename generateAllPlaylists writes, so the format must never drift. */
    private static String masterCacheFilename(boolean direct, boolean transcode, SubtitleFormat subtitleFormat) {
        return String.format(Locale.ROOT, "master_d%d_t%d_s%s" + EXT_M3U8,
                direct ? 1 : 0, transcode ? 1 : 0, subtitleFormat.name());
    }

    private Path getCachedOrGenerateBinary(Path cacheFile, Consumer<Path> generator) throws IOException {
        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return cacheFile;
        }
        Files.createDirectories(cacheFile.getParent());
        generator.accept(cacheFile);
        return cacheFile;
    }

    // ========== Plain-value carriers for the short read-only transactions ==========

    private record StreamPlaylistContext(String filePath, boolean audioOnly, long durationInMilliseconds) {
    }

    private record CopyAudioContext(String filePath, String codecName) {
    }

    private record PassRequestContext(String inputPath, String directoryName) {
    }

    private record SubtitleContext(MediaFileStreamEntity subtitleStream, String mediaFilePath) {
    }
}
