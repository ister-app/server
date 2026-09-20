package app.ister.transcoder;

import app.ister.core.node.MediaFileInputResolver;
import app.ister.core.node.RemoteNodeClient;
import app.ister.core.storage.ObjectRef;
import app.ister.core.storage.ObjectStore;
import app.ister.core.storage.ObjectStoreRegistry;
import app.ister.core.storage.TmpStore;
import app.ister.core.storage.TmpStoreProvider;
import app.ister.core.config.LanguageMatcher;
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
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.amqp.core.AmqpAdmin;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_REQUESTED;

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
    private static final String SEG_VIDEO_PREFIX = "seg_video_";
    private static final String SEG_AUDIO_PREFIX = "seg_audio_";
    private static final String PASS_CATEGORY_VIDEO = "video";
    private static final String PASS_CATEGORY_AUDIO = "audio";

    private final HlsPlaylistBuilder playlistBuilder;
    private final HlsSubtitleService subtitleService;
    private final HlsBitmapSubtitleService bitmapSubtitleService;
    private final HlsTranscodeService transcodeService;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final MessageSender messageSender;
    private final RemoteNodeClient remoteNodeClient;
    private final MediaFileInputResolver inputResolver;
    private final ObjectStoreRegistry objectStoreRegistry;
    private final TmpStoreProvider tmpStoreProvider;
    private final AmqpAdmin amqpAdmin;

    /**
     * Where produced segments/playlists of a file must be pushed to besides the local tmp dir:
     * the requesting node of an S3 file, or the owner of a LOCAL file this helper transcodes.
     * Keyed by media file id, filled when a pass starts, read by the playlist re-upload hook.
     */
    private final ConcurrentHashMap<UUID, String> uploadTargets = new ConcurrentHashMap<>();

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
                      HlsBitmapSubtitleService bitmapSubtitleService,
                      HlsTranscodeService transcodeService, MediaFileRepository mediaFileRepository,
                      MediaFileStreamRepository mediaFileStreamRepository, MessageSender messageSender,
                      RemoteNodeClient remoteNodeClient, MediaFileInputResolver inputResolver,
                      ObjectStoreRegistry objectStoreRegistry, TmpStoreProvider tmpStoreProvider,
                      AmqpAdmin amqpAdmin, PlatformTransactionManager transactionManager) {
        this.amqpAdmin = amqpAdmin;
        this.playlistBuilder = playlistBuilder;
        this.subtitleService = subtitleService;
        this.bitmapSubtitleService = bitmapSubtitleService;
        this.transcodeService = transcodeService;
        this.mediaFileRepository = mediaFileRepository;
        this.mediaFileStreamRepository = mediaFileStreamRepository;
        this.messageSender = messageSender;
        this.remoteNodeClient = remoteNodeClient;
        this.inputResolver = inputResolver;
        this.objectStoreRegistry = objectStoreRegistry;
        this.tmpStoreProvider = tmpStoreProvider;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    /** This node's public URL, as other nodes and clients see it. */
    @Value("${app.ister.server.url:}")
    private String ownUrl;

    @Value("${app.ister.server.hls.master-playlist-timeout-ms:120000}")
    private long masterPlaylistTimeoutMs;

    /** Same budget as HlsTranscodeService.waitForSegment, for segments produced on another node via the shared tmp store. */
    @Value("${app.ister.server.hls.segment-timeout-ms:60000}")
    private long segmentTimeoutMs;

    /** After the pass completes, keep retrying failed segment uploads for at most this long. */
    @Value("${app.ister.transcoder.hls.upload-drain-timeout-ms:300000}")
    private long uploadDrainTimeoutMs;

    /** Per-subtitle locks to prevent duplicate segment generation for the same subtitle stream. */
    private final ConcurrentHashMap<String, Object> subtitleLocks = new ConcurrentHashMap<>();

    /** Per-file locks for on-demand binary generation — see {@link #getCachedOrGenerateBinary}. */
    private final ConcurrentHashMap<Path, Object> binaryGenerationLocks = new ConcurrentHashMap<>();

    /** What a pre-transcode of one media file asked for, so its remaining passes can be resumed. */
    private record PreTranscodeRequest(boolean direct, boolean transcode, PassFilter filter) {
    }

    /** The pre-transcode request per file being warmed up; removed once every pass is done. */
    private final ConcurrentHashMap<UUID, PreTranscodeRequest> preTranscodeRequests = new ConcurrentHashMap<>();

    @PostConstruct
    void registerBackgroundPassResume() {
        transcodeService.setBackgroundPassFinishedListener(this::startNextPendingPass);
        transcodeService.setPlaylistRewrittenListener(this::reuploadPlaylistIfRemote);
    }

    /**
     * A playlist that was corrected after its pass must reach the source node too,
     * or that node keeps serving the version promising a segment nobody has.
     */
    private void reuploadPlaylistIfRemote(Path playlist) {
        try {
            UUID mediaFileId = UUID.fromString(playlist.getParent().getFileName().toString());
            MediaFileEntity mediaFile = readOnlyTransaction.execute(_ ->
                    mediaFileRepository.findById(mediaFileId).orElse(null));
            if (mediaFile == null) return;
            Optional<UploadTarget> target = uploadTarget(mediaFile);
            if (target.isEmpty()) return;
            target.get().upload(mediaFileId, playlist);
        } catch (Exception e) {
            log.warn("Could not re-upload corrected playlist {}: {}", playlist, e.toString());
        }
    }

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

        syncFromShared(mediaFileId, cacheFile);
        if (Files.exists(cacheFile)) {
            String cached = Files.readString(cacheFile);
            if (isCurrentMaster(cached)) {
                Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
                return cached;
            }
            log.warn("Stale master playlist cache for {} (no stream entries or old generation), deleting and regenerating", mediaFileId);
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
        requireTranscodeQueue(directoryName, mediaFileId);
        log.debug("Master playlist cache miss for {}, sending TRANSCODE_REQUESTED to directory queue {}", mediaFileId, directoryName);

        TranscodeRequestedData request = TranscodeRequestedData.builder()
                .eventType(EventType.TRANSCODE_REQUESTED)
                .mediaFileId(mediaFileId)
                .direct(direct)
                .transcode(transcode)
                .subtitleFormat(subtitleFormat)
                .requestingNodeUrl(ownUrl)
                .build();
        messageSender.sendTranscodeRequested(request, directoryName);

        try {
            return waitForMasterPlaylist(mediaFileId, cacheFile, masterPlaylistTimeoutMs / 2);
        } catch (IOException _) {
            // The event may have been lost or dead-lettered; re-issue it once before giving up.
            log.warn("Master playlist for {} not produced in time, re-sending TRANSCODE_REQUESTED once", mediaFileId);
            messageSender.sendTranscodeRequested(request, directoryName);
            return waitForMasterPlaylist(mediaFileId, cacheFile, masterPlaylistTimeoutMs / 2);
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
        generateAllPlaylists(mediaFileId, direct, transcode, subtitleFormat, null);
    }

    /** @param requestingNodeUrl see {@link TranscodeRequestedData#getRequestingNodeUrl()} */
    public void generateAllPlaylists(UUID mediaFileId, boolean direct, boolean transcode, SubtitleFormat subtitleFormat,
                                     String requestingNodeUrl) throws IOException {
        rememberUploadTarget(mediaFileId, requestingNodeUrl);
        Path cacheFile = cacheDir(mediaFileId).resolve(masterCacheFilename(direct, transcode, subtitleFormat));

        // Duplicate TRANSCODE_REQUESTED events are common (poll-timeout re-sends,
        // scan-time pre-generation); keep that path DB-free.
        if (Files.exists(cacheFile) && isCurrentMaster(Files.readString(cacheFile))) {
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
                inputResolver.remoteNodeUrl(entity);
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

        Optional<UploadTarget> target = uploadTarget(mediaFile);
        if (target.isPresent()) {
            try (Stream<Path> files = Files.list(cacheDir(mediaFileId))) {
                files.filter(p -> p.toString().endsWith(EXT_M3U8))
                        .forEach(p -> {
                            try {
                                target.get().upload(mediaFileId, p);
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
        rememberUploadTarget(data.getMediaFileId(), data.getRequestingNodeUrl());
        Optional<UploadTarget> target = uploadTarget(mediaFile);
        boolean background = Boolean.TRUE.equals(data.getBackground());
        String mediaFilePath = resolveInputPath(mediaFile);

        Path cacheDirPath = cacheDir(data.getMediaFileId());
        String segmentPrefix;
        Runnable passStarter;

        if (PASS_CATEGORY_VIDEO.equals(data.getPassCategory())) {
            VideoQuality vq = VideoQuality.fromLabel(data.getQualityLabel());
            segmentPrefix = SEG_VIDEO_PREFIX + data.getQualityLabel() + "_";
            passStarter = () -> transcodeService.startVideoPass(mediaFilePath, cacheDirPath, vq, background);
        } else {
            AudioQuality aq = AudioQuality.fromLabel(data.getQualityLabel());
            int audioStreamIndex = data.getAudioStreamIndex();
            segmentPrefix = SEG_AUDIO_PREFIX + audioStreamIndex + "_" + data.getQualityLabel() + "_";
            String sourceCodec = mediaFile.getMediaFileStreamEntity().stream()
                    .filter(s -> s.getStreamIndex() == audioStreamIndex)
                    .map(MediaFileStreamEntity::getCodecName)
                    .findFirst()
                    .orElse("");
            passStarter = () -> transcodeService.startAudioPass(mediaFilePath, cacheDirPath,
                    audioStreamIndex, aq, sourceCodec, background);
        }

        transcodeService.ensurePassStarted(data.getPassKey(), passStarter, background);

        if (target.isPresent()) {
            UploadTarget uploadTarget = target.get();
            UUID mediaFileId = data.getMediaFileId();
            CompletableFuture<Void> passFuture = transcodeService.getActiveFuture(data.getPassKey());
            watcherExecutor.submit(() ->
                    watchAndUpload(cacheDirPath, segmentPrefix, uploadTarget, mediaFileId, passFuture));
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
        startPasses(mediaFileId, direct, transcode, PassFilter.none());
    }

    /**
     * @param filter which passes are worth producing — see {@link PassFilter}
     */
    @Transactional(readOnly = true)
    public void startAllPasses(UUID mediaFileId, boolean direct, boolean transcode, PassFilter filter) {
        startPasses(mediaFileId, direct, transcode, filter);
    }

    private void startPasses(UUID mediaFileId, boolean direct, boolean transcode, PassFilter filter) {
        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
        PreTranscodeRequest request = new PreTranscodeRequest(direct, transcode, filter);
        preTranscodeRequests.put(mediaFileId, request);
        pendingPasses(mediaFileId, mediaFile, request).forEach(pass -> doStartPass(pass, mediaFile));
    }

    /**
     * Starts the next pass of a pre-transcoded file that is not transcoded yet; called by
     * {@link HlsTranscodeService} whenever a background pass frees its budget. Passes that find
     * no budget at request time are dropped rather than queued, so without this a file gains only
     * as many passes per pre-transcode cycle as the background budget allows — a file with many
     * audio streams would need days to finish.
     */
    private void startNextPendingPass(UUID mediaFileId) {
        PreTranscodeRequest request = preTranscodeRequests.get(mediaFileId);
        if (request == null) {
            return;
        }
        readOnlyTransaction.executeWithoutResult(status ->
                mediaFileRepository.findById(mediaFileId).ifPresentOrElse(mediaFile -> {
                    List<TranscodePassRequestedData> pending = pendingPasses(mediaFileId, mediaFile, request);
                    if (pending.isEmpty()) {
                        // A pass that is registered but still queued (waiting for a pool thread) counts
                        // as active, not pending — so an empty list can mean "all remaining passes are
                        // momentarily in flight", not "all done". Only retire the resume request once no
                        // pass for the file is still active; otherwise a queued pass that later drops for
                        // lack of budget would never be pulled back in and the file would stall.
                        if (!transcodeService.hasActivePassForFile(mediaFileId)) {
                            preTranscodeRequests.remove(mediaFileId);
                            log.debug("All pre-transcode passes finished for {}", mediaFileId);
                        }
                        return;
                    }
                    log.debug("Starting next pre-transcode pass for {} ({} pending)", mediaFileId, pending.size());
                    doStartPass(pending.getFirst(), mediaFile);
                }, () -> preTranscodeRequests.remove(mediaFileId)));
    }

    /** The passes of this media file that are neither running nor already completed on disk. */
    private List<TranscodePassRequestedData> pendingPasses(UUID mediaFileId, MediaFileEntity mediaFile,
                                                           PreTranscodeRequest request) {
        String inputPath = resolveInputPath(mediaFile);
        PassFilter filter = request.filter();
        List<TranscodePassRequestedData> pending = new ArrayList<>();

        boolean[] includeQuality = {request.direct(), request.transcode(), request.transcode()};
        VideoQuality[] videoQualities = VideoQuality.values();
        AudioQuality[] audioQualities = AudioQuality.values();

        List<MediaFileStreamEntity> audioStreams = audioStreamsToTranscode(mediaFile, filter);

        // Never start video passes for a file without a real video stream: FFmpeg's
        // -map 0:v:0 would grab embedded cover art and fail (or transcode a JPEG).
        boolean hasVideo = mediaFile.getMediaFileStreamEntity().stream()
                .anyMatch(HlsPlaylistBuilder::isRealVideoStream);
        for (int i = 0; hasVideo && i < videoQualities.length; i++) {
            if (includeQuality[i] && !exceedsQualityCap(videoQualities[i], filter)) {
                pendingVideoPass(mediaFileId, inputPath, videoQualities[i]).ifPresent(pending::add);
            }
        }

        for (int qi = 0; qi < audioQualities.length; qi++) {
            AudioQuality aq = audioQualities[qi];
            if (includeQuality[qi] && producesAudioPass(aq, filter)) {
                String qualityLabel = aq.getLabel();
                for (MediaFileStreamEntity audioStream : audioStreams) {
                    pendingAudioPass(mediaFileId, inputPath, qualityLabel, audioStream.getStreamIndex()).ifPresent(pending::add);
                }
            }
        }
        return pending;
    }

    /**
     * No master playlist ever points at the 64k group — the builder folds it into 192k — so producing
     * it in the background is wasted work. Interactive requests still get it on demand.
     * COPY audio is a real segmented pass (it used to be a single on-demand whole-file segment),
     * so it warms up in the background alongside the copy video pass whenever direct is requested.
     */
    private static boolean producesAudioPass(AudioQuality quality, PassFilter filter) {
        return !(filter.preTranscode() && quality == AudioQuality.Q64K);
    }

    private static boolean exceedsQualityCap(VideoQuality quality, PassFilter filter) {
        return filter.maxVideoHeight() != null
                && quality.getHeight() != null
                && quality.getHeight() > filter.maxVideoHeight();
    }

    /**
     * The audio streams worth transcoding: the ones in a preferred language, or all of them when no
     * preference was given. A file whose streams match no preference still gets its first stream, so
     * a pre-transcoded file is never warm but mute.
     */
    private static List<MediaFileStreamEntity> audioStreamsToTranscode(MediaFileEntity mediaFile, PassFilter filter) {
        List<MediaFileStreamEntity> audioStreams = mediaFile.getMediaFileStreamEntity().stream()
                .filter(s -> s.getCodecType() == StreamCodecType.AUDIO)
                .toList();
        if (filter.audioLanguages().isEmpty() || audioStreams.isEmpty()) {
            return audioStreams;
        }
        List<MediaFileStreamEntity> preferred = audioStreams.stream()
                .filter(s -> LanguageMatcher.matches(s.getLanguage(), filter.audioLanguages()))
                .toList();
        return preferred.isEmpty() ? List.of(audioStreams.getFirst()) : preferred;
    }

    private Optional<TranscodePassRequestedData> pendingVideoPass(UUID mediaFileId, String inputPath, VideoQuality vq) {
        String qualityLabel = vq.getLabel();
        String passKey = mediaFileId + "_video_" + qualityLabel;
        if (transcodeService.isPassActive(passKey) || transcodeService.hasCompletedPass(passKey)) return Optional.empty();
        if (transcodeService.hasDoneMarker(cacheDir(mediaFileId), SEG_VIDEO_PREFIX + qualityLabel + "_")) {
            log.debug("Skipping video pass for {} quality={} — pass already completed on disk", mediaFileId, qualityLabel);
            return Optional.empty();
        }
        return Optional.of(TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .mediaFileId(mediaFileId)
                .passKey(passKey)
                .mediaFilePath(inputPath)
                .passCategory(PASS_CATEGORY_VIDEO)
                .qualityLabel(qualityLabel)
                .background(true)
                .requestingNodeUrl(requesterOf(mediaFileId))
                .build());
    }

    private Optional<TranscodePassRequestedData> pendingAudioPass(UUID mediaFileId, String inputPath,
                                                                  String qualityLabel, int streamIdx) {
        String passKey = mediaFileId + "_audio_" + streamIdx + "_" + qualityLabel;
        if (transcodeService.isPassActive(passKey) || transcodeService.hasCompletedPass(passKey)) return Optional.empty();
        if (transcodeService.hasDoneMarker(cacheDir(mediaFileId), SEG_AUDIO_PREFIX + streamIdx + "_" + qualityLabel + "_")) {
            log.debug("Skipping audio pass for {} streamIdx={} quality={} — pass already completed on disk", mediaFileId, streamIdx, qualityLabel);
            return Optional.empty();
        }
        return Optional.of(TranscodePassRequestedData.builder()
                .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                .mediaFileId(mediaFileId)
                .passKey(passKey)
                .mediaFilePath(inputPath)
                .passCategory(PASS_CATEGORY_AUDIO)
                .qualityLabel(qualityLabel)
                .audioStreamIndex(streamIdx)
                .background(true)
                .requestingNodeUrl(requesterOf(mediaFileId))
                .build());
    }

    /**
     * Returns (cached) stream playlist content.
     * Filename determines the type: stream_video_*, stream_audio_*, or stream_sub_*.
     */
    public String getStreamPlaylist(UUID mediaFileId, String streamFilename) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(streamFilename);
        syncFromShared(mediaFileId, cacheFile);
        if (isCurrentPlaylist(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readString(cacheFile);
        }
        StreamPlaylistContext ctx = readOnlyTransaction.execute(status -> {
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            return new StreamPlaylistContext(resolveInputPath(mediaFile), isAudioOnly(mediaFile),
                    mediaFile.getDurationInMilliseconds());
        });
        seedProbeCachesForAudioOnly(ctx);
        // The grid is trimmed to where this particular stream ends, and the pass is
        // started from the very same call — so the playlist can only advertise
        // segments FFmpeg will write. Audio-only files have no keyframes; the grid
        // falls back to a synthetic one that the pass uses too.
        SegmentGrid grid = transcodeService.gridFor(ctx.filePath(),
                HlsPlaylistBuilder.roleOf(streamFilename));
        Files.createDirectories(cacheFile.getParent());
        String content = playlistBuilder.buildStreamPlaylist(streamFilename, grid.starts(), grid.end());
        writePlaylistAtomically(cacheFile, content);
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
        String segmentPrefix = SEG_VIDEO_PREFIX + qualityLabel + "_";
        syncSegmentFromShared(mediaFileId, cacheFile, segmentPrefix);
        Path completed = completedSegmentOrNull(mediaFileId, cacheFile, segmentPrefix);
        if (completed != null) return completed;
        requestPassIfNeeded(mediaFileId, passKey, PASS_CATEGORY_VIDEO, qualityLabel, null);
        return waitForSegment(mediaFileId, cacheFile, passKey, segmentPrefix);
    }

    /**
     * Returns path to (cached) audio-only .ts segment.
     * <p>
     * All qualities (including {@code copy}) use {@code seg_audio_{streamIdx}_{bitrate}_%05d.ts},
     * produced by a background FFmpeg pass; the first request for a quality sends a
     * {@code TRANSCODE_PASS_REQUESTED} event.
     * Legacy format {@code seg_audio_{start}_{duration}_{streamIdx}_copy.ts} (one segment spanning
     * the whole file) is still generated on demand for playlists cached before copy audio became
     * segmented; the cache retention window retires those.
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
        String segmentPrefix = SEG_AUDIO_PREFIX + streamIdx + "_" + bitrateLabel + "_";
        syncSegmentFromShared(mediaFileId, cacheFile, segmentPrefix);
        Path completed = completedSegmentOrNull(mediaFileId, cacheFile, segmentPrefix);
        if (completed != null) return completed;
        requestPassIfNeeded(mediaFileId, passKey, PASS_CATEGORY_AUDIO, bitrateLabel, streamIdx);
        return waitForSegment(mediaFileId, cacheFile, passKey, segmentPrefix);
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
                        .requestingNodeUrl(requesterOf(mediaFileId))
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
        // Serve the cache only when it was written by the current generation:
        // older generations lacked the cue-duration sanitizing and their
        // segments make players pile up eleven-minute cues.
        if (Files.exists(cacheFile) && subtitleService.isGenerationCurrent(cacheDir(mediaFileId), subtitleId)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readString(cacheFile, StandardCharsets.UTF_8);
        }
        Files.createDirectories(cacheFile.getParent());

        // The subtitle stream entity is fully loaded here; generation below only reads its
        // basic fields (id, codecType, path, streamIndex), never lazy associations.
        SubtitleContext ctx = readOnlyTransaction.execute(status -> {
            MediaFileStreamEntity subtitleStream = mediaFileStreamRepository.findById(subtitleId).orElseThrow();
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            return new SubtitleContext(subtitleStream, resolveInputPath(mediaFile), externalSrtUrl(subtitleStream, mediaFile),
                    externalSrtStore(subtitleStream, mediaFile));
        });
        MediaFileStreamEntity subtitleStream = ctx.subtitleStream();
        String mediaFilePath = ctx.mediaFilePath();
        String externalSrtPath = externalSrtLocal(ctx, mediaFileId);
        String generationKey = mediaFileId + "_sub_" + subtitleId;
        Object lock = subtitleLocks.computeIfAbsent(generationKey, k -> new Object());
        synchronized (lock) {
            if (!Files.exists(cacheFile)
                    || !subtitleService.isGenerationCurrent(cacheDir(mediaFileId), subtitleId)) {
                subtitleService.generateSubtitleSegments(subtitleStream, mediaFilePath, externalSrtPath, mediaFileId,
                        cacheDir(mediaFileId), transcodeService.gridFor(mediaFilePath, StreamRole.subtitle()));
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
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            String mediaFilePath = stream.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE ? null
                    : resolveInputPath(mediaFile);
            return new SubtitleContext(stream, mediaFilePath, externalSrtUrl(stream, mediaFile), externalSrtStore(stream, mediaFile));
        });
        MediaFileStreamEntity stream = ctx.subtitleStream();

        String sourceSrtPath;
        if (stream.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE) {
            sourceSrtPath = externalSrtLocal(ctx, mediaFileId);
        } else {
            Files.createDirectories(cacheDir(mediaFileId));
            sourceSrtPath = subtitleService.extractEmbeddedSubtitleToSrt(stream, ctx.mediaFilePath(), cacheDir(mediaFileId));
        }

        // Generation-stamped name: pre-sanitizing offset files must not be
        // served from cache forever (see HlsSubtitleService.SUBTITLE_GENERATION).
        Path offsetPath = cacheDir(mediaFileId).resolve(
                "sub_" + subtitleId + "_offset_g" + HlsSubtitleService.SUBTITLE_GENERATION + ".srt");
        subtitleService.writeSrtWithOffset(sourceSrtPath, offsetPath);
        return offsetPath;
    }

    /**
     * Returns a (cached) bitmap subtitle artifact: the cue index {@code bsub_{streamId}.json}
     * or one of its sprite sheets {@code bsub_{streamId}_{NN}.png}. The first miss generates
     * the artifacts of <em>every</em> bitmap stream of the file — reading the container once
     * is the whole cost, so the next language is free.
     */
    public Path getBitmapSubtitleFile(UUID mediaFileId, String fileName) throws IOException {
        UUID streamId = HlsBitmapSubtitleService.streamIdOf(fileName);
        Path file = cacheDir(mediaFileId).resolve(fileName);
        if (!bitmapSubtitleReady(mediaFileId, streamId, file)) {
            generateBitmapSubtitles(mediaFileId);
        }
        if (!Files.exists(file)) {
            throw new NoSuchElementException("No bitmap subtitle file " + fileName);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        return file;
    }

    /** Local first, then the shared tmp store: another node may have generated them already. */
    private boolean bitmapSubtitleReady(UUID mediaFileId, UUID streamId, Path file) {
        Path dir = cacheDir(mediaFileId);
        syncFromShared(mediaFileId, HlsBitmapSubtitleService.generationMarker(dir, streamId));
        return bitmapSubtitleService.isGenerationCurrent(dir, streamId) && syncFromShared(mediaFileId, file);
    }

    private void generateBitmapSubtitles(UUID mediaFileId) throws IOException {
        // Streams are fully loaded here; generation only reads their basic fields.
        BitmapSubtitleContext ctx = readOnlyTransaction.execute(status -> {
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            List<MediaFileStreamEntity> streams = mediaFile.getMediaFileStreamEntity().stream()
                    .filter(HlsBitmapSubtitleService::isSupported)
                    .toList();
            return new BitmapSubtitleContext(resolveInputPath(mediaFile), streams);
        });
        if (ctx.streams().isEmpty()) {
            return;
        }
        Path dir = cacheDir(mediaFileId);
        Object lock = subtitleLocks.computeIfAbsent(mediaFileId + "_bsub", k -> new Object());
        synchronized (lock) {
            List<MediaFileStreamEntity> missing = ctx.streams().stream()
                    .filter(s -> {
                        syncFromShared(mediaFileId, HlsBitmapSubtitleService.generationMarker(dir, s.getId()));
                        return !bitmapSubtitleService.isGenerationCurrent(dir, s.getId());
                    })
                    .toList();
            if (missing.isEmpty()) {
                return;
            }
            List<Path> written = bitmapSubtitleService.generate(ctx.mediaFilePath(), missing, dir);
            Optional<TmpStore> shared = tmpStoreProvider.shared();
            if (shared.isPresent()) {
                // markers come after their files in the list, so a reader never sees a marker without them
                for (Path file : written) {
                    shared.get().put(mediaFileId, file);
                }
            }
        }
    }

    /**
     * Starts the bitmap subtitle generation in the background while playback is being set up,
     * so the index is usually there by the time a viewer picks the track. On a virtual thread,
     * not the transcode executor: it is I/O bound and must not cost a transcode slot.
     */
    private void warmBitmapSubtitles(UUID mediaFileId, List<MediaFileStreamEntity> streams) {
        if (streams.stream().noneMatch(HlsBitmapSubtitleService::isSupported)) {
            return;
        }
        Thread.ofVirtual().name("bsub-warm-" + mediaFileId).start(() -> {
            try {
                generateBitmapSubtitles(mediaFileId);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not pre-generate bitmap subtitles for {}: {}", mediaFileId, e.toString());
            }
        });
    }

    // ========== Stream playlist pre-generation ==========

    private void preGenerateStreamPlaylists(MediaFileEntity mediaFile, UUID mediaFileId,
                                             boolean direct, boolean transcode,
                                             SubtitleFormat subtitleFormat) throws IOException {
        String filePath = resolveInputPath(mediaFile);
        boolean audioOnly = isAudioOnly(mediaFile);
        seedProbeCachesForAudioOnly(new StreamPlaylistContext(filePath, audioOnly,
                mediaFile.getDurationInMilliseconds()));
        double totalDuration = transcodeService.getTotalDuration(filePath);

        List<MediaFileStreamEntity> streams = mediaFile.getMediaFileStreamEntity();
        List<MediaFileStreamEntity> audioStreams = streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.AUDIO)
                .toList();
        // Same filter as HlsPlaylistBuilder: image subtitles never get a
        // rendition in the master, so pre-writing their playlists only
        // produces orphans.
        List<MediaFileStreamEntity> subtitleStreams = streams.stream()
                .filter(s -> (s.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE
                        || s.getCodecType() == StreamCodecType.SUBTITLE)
                        && !HlsPlaylistBuilder.isImageSubtitle(s))
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
        if (transcodeService.getCachedKeyframes(filePath).isEmpty() && !audioOnly) {
            log.warn("No keyframes found for {}, falling back to a synthetic grid", filePath);
        }

        if (hasVideoStream) {
            for (int i = 0; i < videoQualities.length; i++) {
                if (!includeVideo[i]) continue;
                VideoQuality vq = videoQualities[i];
                String filename = "stream_video_" + vq.getLabel() + EXT_M3U8;
                String qualityLabel = vq.getLabel();
                SegmentGrid grid = transcodeService.gridFor(filePath,
                        vq == VideoQuality.COPY ? StreamRole.videoCopy() : StreamRole.videoEncode());
                writeStreamPlaylistIfAbsent(mediaFileId, filename, grid,
                        (start, dur, idx) -> String.format(Locale.ROOT, "seg_video_%s_%05d.ts", qualityLabel, idx));
            }
        }

        preGenerateAudioPlaylists(mediaFileId, filePath, audioStreams, audioQualities, includeVideo);
        preGenerateSubtitlePlaylists(mediaFileId, filePath, subtitleStreams, subtitleFormat, totalDuration);
        warmBitmapSubtitles(mediaFileId, streams);
    }

    private void preGenerateAudioPlaylists(UUID mediaFileId, String filePath,
                                            List<MediaFileStreamEntity> audioStreams,
                                            AudioQuality[] audioQualities, boolean[] includeVideo) throws IOException {
        for (int qi = 0; qi < audioQualities.length; qi++) {
            if (!includeVideo[qi]) continue;
            AudioQuality aq = audioQualities[qi];
            for (MediaFileStreamEntity as : audioStreams) {
                // Copy audio is segmented like every other quality — see HlsPlaylistBuilder.
                String filename = String.format(Locale.ROOT, "stream_audio_%d_%s" + EXT_M3U8, as.getStreamIndex(), aq.getLabel());
                int streamIndex = as.getStreamIndex();
                String bitrateLabel = aq.getLabel();
                SegmentGrid grid = transcodeService.gridFor(filePath, StreamRole.audio(streamIndex));
                writeStreamPlaylistIfAbsent(mediaFileId, filename, grid,
                        (start, dur, idx) -> String.format(Locale.ROOT, "seg_audio_%d_%s_%05d.ts", streamIndex, bitrateLabel, idx));
            }
        }
    }

    private void preGenerateSubtitlePlaylists(UUID mediaFileId, String filePath,
                                               List<MediaFileStreamEntity> subtitleStreams,
                                               SubtitleFormat subtitleFormat, double totalDuration) throws IOException {
        String formatLabel = subtitleFormat.name().toLowerCase();
        for (MediaFileStreamEntity ss : subtitleStreams) {
            String filename = "stream_sub_" + ss.getId() + "_" + formatLabel + EXT_M3U8;
            if (subtitleFormat == SubtitleFormat.SRT) {
                writePlaylistIfAbsent(mediaFileId, filename,
                        playlistBuilder.buildSingleSegmentPlaylist(totalDuration, "sub_" + ss.getId() + ".srt"));
            } else {
                UUID ssId = ss.getId();
                // Subtitle segments are written by HlsSubtitleService itself, so they
                // always exist: this grid is deliberately untrimmed.
                SegmentGrid grid = transcodeService.gridFor(filePath, StreamRole.subtitle());
                writeStreamPlaylistIfAbsent(mediaFileId, filename, grid,
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

    private void writeStreamPlaylistIfAbsent(UUID mediaFileId, String filename, SegmentGrid grid,
                                              HlsPlaylistBuilder.SegmentNamer namer) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(filename);
        if (!isCurrentPlaylist(cacheFile)) {
            writePlaylistAtomically(cacheFile, playlistBuilder.buildVodPlaylist(grid.starts(), grid.end(), namer));
        }
    }

    /**
     * Whether a cached playlist was written with the current grid generation. One
     * from before is treated as absent and regenerated — otherwise a directory
     * cached while playlists could over-promise keeps serving that promise.
     */
    private static boolean isCurrentPlaylist(Path cacheFile) throws IOException {
        return Files.exists(cacheFile) && Files.readString(cacheFile).contains(HlsPlaylistBuilder.GRID_TAG);
    }

    /**
     * Writes a playlist through a temporary file: readers do a plain readString,
     * and must see either the whole old file or the whole new one.
     */
    static void writePlaylistAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException _) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
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
        return inputResolver.isRemote(mediaFile);
    }

    /** A requester that is another node becomes the push target for everything this node produces for the file. */
    private void rememberUploadTarget(UUID mediaFileId, String requestingNodeUrl) {
        if (mediaFileId == null || requestingNodeUrl == null || requestingNodeUrl.isBlank()) {
            return;
        }
        if (ownUrl != null && !ownUrl.isBlank() && stripSlash(ownUrl).equals(stripSlash(requestingNodeUrl))) {
            uploadTargets.remove(mediaFileId);
        } else {
            uploadTargets.put(mediaFileId, stripSlash(requestingNodeUrl));
        }
    }

    private String requesterOf(UUID mediaFileId) {
        return mediaFileId == null ? null : uploadTargets.get(mediaFileId);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The node the produced segments and playlists must be pushed to, if any: the node that
     * requested playback when that is not this node (an S3 file transcoded by another attached
     * node, or a helper), else — for a LOCAL file owned elsewhere — its owner. Empty when this
     * node serves the file itself.
     */
    private Optional<UploadTarget> uploadTarget(MediaFileEntity mediaFile) {
        Optional<TmpStore> shared = tmpStoreProvider.shared();
        if (shared.isPresent()) {
            // Everything goes to the cluster-shared store; whoever serves the playback reads
            // it through from there, so the requester and the owner no longer matter.
            return Optional.of(new SharedTmpTarget(shared.get()));
        }
        String requester = requesterOf(mediaFile.getId());
        if (requester != null) {
            return Optional.of(new NodeTarget(requester));
        }
        return inputResolver.remoteNodeUrl(mediaFile).map(NodeTarget::new);
    }

    /** Where a produced file must be published besides the local tmp dir. */
    private interface UploadTarget {
        void upload(UUID mediaFileId, Path file) throws IOException;
    }

    private final class NodeTarget implements UploadTarget {
        private final String nodeUrl;

        NodeTarget(String nodeUrl) {
            this.nodeUrl = nodeUrl;
        }

        @Override
        public void upload(UUID mediaFileId, Path file) throws IOException {
            remoteNodeClient.uploadFile(nodeUrl, mediaFileId, file);
        }

        @Override
        public String toString() {
            return nodeUrl;
        }
    }

    private static final class SharedTmpTarget implements UploadTarget {
        private final TmpStore store;

        SharedTmpTarget(TmpStore store) {
            this.store = store;
        }

        @Override
        public void upload(UUID mediaFileId, Path file) throws IOException {
            store.put(mediaFileId, file);
        }

        @Override
        public String toString() {
            return "shared tmp store";
        }
    }

    // ========== Shared tmp store: read-through ==========

    /**
     * Pulls a playlist/marker of the media file from the shared tmp store into the local tmp dir
     * when it is not there yet. Local first: the node that ran the pass never round-trips.
     */
    private boolean syncFromShared(UUID mediaFileId, Path localFile) {
        if (Files.exists(localFile)) {
            return true;
        }
        Optional<TmpStore> shared = tmpStoreProvider.shared();
        if (shared.isEmpty()) {
            return false;
        }
        try {
            return shared.get().copyToLocal(mediaFileId, localFile.getFileName().toString(), localFile);
        } catch (IOException e) {
            log.debug("Could not read {} from the shared tmp store: {}", localFile.getFileName(), e.getMessage());
            return false;
        }
    }

    /** A segment plus, when the pass finished elsewhere, its done marker, so the local fast path applies. */
    private void syncSegmentFromShared(UUID mediaFileId, Path segmentFile, String segmentPrefix) {
        if (tmpStoreProvider.shared().isEmpty()) {
            return;
        }
        syncFromShared(mediaFileId, cacheDir(mediaFileId).resolve(HlsTranscodeService.DONE_MARKER_PREFIX + segmentPrefix));
        syncFromShared(mediaFileId, segmentFile);
    }

    /**
     * Waits for a segment: the local pass, and — with a shared tmp store — a pass running on
     * another attached node, whose finished segments appear in the store one by one.
     */
    private Path waitForSegment(UUID mediaFileId, Path cacheFile, String passKey, String segmentPrefix) throws IOException {
        if (tmpStoreProvider.shared().isEmpty() || transcodeService.isPassActive(passKey)) {
            return transcodeService.waitForSegment(cacheFile, passKey);
        }
        long deadline = System.currentTimeMillis() + segmentTimeoutMs;
        long delay = 250;
        while (System.currentTimeMillis() < deadline) {
            if (transcodeService.isPassActive(passKey)) {
                // this node picked the pass up after all
                return transcodeService.waitForSegment(cacheFile, passKey);
            }
            syncSegmentFromShared(mediaFileId, cacheFile, segmentPrefix);
            Path stable = transcodeService.stableSegmentOrNull(cacheFile);
            if (stable != null) {
                return stable;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for segment");
            }
            delay = Math.min(delay * 2, 1000);
        }
        throw new IOException("Timeout waiting for HLS segment from the shared tmp store: " + cacheFile);
    }

    private String resolveInputPath(MediaFileEntity mediaFile) {
        return inputResolver.resolve(mediaFile);
    }

    /** Inside the read transaction: the store of a sidecar .srt in an S3 directory this node is attached to, else null. */
    private ObjectStore externalSrtStore(MediaFileStreamEntity stream, MediaFileEntity mediaFile) {
        if (stream.getCodecType() != StreamCodecType.EXTERNAL_SUBTITLE || !ObjectRef.isS3Uri(stream.getPath())
                || isRemote(mediaFile)) {
            return null;
        }
        return objectStoreRegistry.forUri(stream.getPath()).orElse(null);
    }

    /** Inside the read transaction: the owner's download URL for a remote external subtitle, else null. */
    private String externalSrtUrl(MediaFileStreamEntity stream, MediaFileEntity mediaFile) {
        if (stream.getCodecType() != StreamCodecType.EXTERNAL_SUBTITLE || !isRemote(mediaFile)) {
            return null;
        }
        return inputResolver.subtitleDownloadUrl(mediaFile, stream);
    }

    /**
     * Where the SRT of an external subtitle stream can be read on this node. Its {@code path}
     * is local to the owning node (cache directory or a sidecar next to the media), so when the
     * file is remote it is fetched once into this file's transcode cache dir; the tmp cleanup
     * removes it together with the segments.
     */
    private String externalSrtLocal(SubtitleContext ctx, UUID mediaFileId) throws IOException {
        MediaFileStreamEntity stream = ctx.subtitleStream();
        if (stream.getCodecType() != StreamCodecType.EXTERNAL_SUBTITLE) {
            return null;
        }
        if (ctx.externalSrtUrl() == null && ctx.externalSrtStore() == null) {
            return stream.getPath();
        }
        Path local = cacheDir(mediaFileId).resolve("ext_" + stream.getId() + ".srt");
        if (!Files.exists(local)) {
            Files.createDirectories(local.getParent());
            if (ctx.externalSrtUrl() != null) {
                remoteNodeClient.downloadToFile(ctx.externalSrtUrl(), local);
            } else {
                // a sidecar .srt in the S3 library directory this node is attached to
                ctx.externalSrtStore().copyToLocal(ObjectRef.parse(stream.getPath()).key(), local);
            }
        }
        return local.toString();
    }

    private void watchAndUpload(Path cacheDirPath, String prefix, UploadTarget target,
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
                }
                running = System.currentTimeMillis() <= drainDeadline;
                if (!running) {
                    log.warn("Giving up uploading remaining segments for {} after {} ms", cacheDirPath, uploadDrainTimeoutMs);
                }
            }
            running = running && scanAndUploadBatch(cacheDirPath, prefix, target, mediaFileId, uploaded);
        }
        if (passFuture.isDone() && !passFuture.isCompletedExceptionally()) {
            uploadDoneMarker(cacheDirPath, prefix, target, mediaFileId);
        }
    }

    /**
     * The done marker goes last: a reader that sees it may serve every segment without waiting on
     * stability, so it must never arrive before the segments themselves.
     */
    private void uploadDoneMarker(Path cacheDirPath, String prefix, UploadTarget target, UUID mediaFileId) {
        Path marker = cacheDirPath.resolve(HlsTranscodeService.DONE_MARKER_PREFIX + prefix);
        if (!Files.exists(marker)) {
            return;
        }
        try {
            target.upload(mediaFileId, marker);
        } catch (IOException e) {
            log.warn("Done marker upload failed for {}: {}", marker, e.getMessage());
        }
    }

    private boolean scanAndUploadBatch(Path cacheDirPath, String prefix, UploadTarget target,
                                        UUID mediaFileId, Set<String> uploaded) {
        try (Stream<Path> files = Files.list(cacheDirPath)) {
            files.filter(p -> p.getFileName().toString().startsWith(prefix)
                           && p.getFileName().toString().endsWith(".ts")
                           && !uploaded.contains(p.getFileName().toString()))
                 .forEach(p -> tryUploadSegment(target, mediaFileId, p, uploaded));
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

    private void tryUploadSegment(UploadTarget target, UUID mediaFileId, Path p, Set<String> uploaded) {
        try {
            if (transcodeService.stableSegmentOrNull(p) != null) {
                target.upload(mediaFileId, p);
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

    /**
     * Transcode events are published to the default exchange with the queue name as routing key,
     * so a message for a queue no node declared is discarded by the broker without a trace. Rather
     * than pinning a request thread for the full master-playlist timeout waiting for a playlist
     * that can never arrive, fail immediately when the target queue does not exist.
     */
    private void requireTranscodeQueue(String directoryName, UUID mediaFileId) throws IOException {
        String queue = APP_ISTER_SERVER_TRANSCODE_REQUESTED + "." + directoryName;
        if (amqpAdmin.getQueueProperties(queue) == null) {
            throw new IOException("No transcoder is listening on queue " + queue
                    + " for media file " + mediaFileId + "; its directory is not served by any node");
        }
    }

    private String waitForMasterPlaylist(UUID mediaFileId, Path cacheFile, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            syncFromShared(mediaFileId, cacheFile);
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

    /**
     * A cached master is only served when it has stream entries and carries the
     * current {@link HlsPlaylistBuilder#MASTER_TAG}; anything older is regenerated,
     * so a bump of that tag repairs every cache directory on its next request.
     */
    private static boolean isCurrentMaster(String cached) {
        return (cached.contains(EXT_X_MEDIA) || cached.contains(EXT_X_STREAM_INF))
                && cached.contains(HlsPlaylistBuilder.MASTER_TAG);
    }

    /**
     * Single source of the master-playlist cache key: getMasterPlaylist polls for the
     * exact filename generateAllPlaylists writes, so the format must never drift.
     */
    private static String masterCacheFilename(boolean direct, boolean transcode, SubtitleFormat subtitleFormat) {
        return String.format(Locale.ROOT, "master_d%d_t%d_s%s" + EXT_M3U8,
                direct ? 1 : 0, transcode ? 1 : 0, subtitleFormat.name());
    }

    /**
     * Returns the cached file, generating it first when absent. The generator writes to a
     * {@code .part} sibling that is atomically moved into place on success: the cache file
     * either does not exist or is complete, so a request arriving mid-generation can never
     * be served a half-written file (the client would see it as a corrupt segment and, for
     * ffmpeg-based players, retry from byte 0 in a loop). Concurrent requests for the same
     * file share one generation via a per-file lock instead of racing a second FFmpeg run.
     */
    private Path getCachedOrGenerateBinary(Path cacheFile, Consumer<Path> generator) throws IOException {
        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return cacheFile;
        }
        Files.createDirectories(cacheFile.getParent());
        Object lock = binaryGenerationLocks.computeIfAbsent(cacheFile, k -> new Object());
        try {
            synchronized (lock) {
                if (!Files.exists(cacheFile)) {
                    Path partFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".part");
                    generator.accept(partFile);
                    Files.move(partFile, cacheFile,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            binaryGenerationLocks.remove(cacheFile);
        }
        return cacheFile;
    }

    // ========== Plain-value carriers for the short read-only transactions ==========

    private record StreamPlaylistContext(String filePath, boolean audioOnly, long durationInMilliseconds) {
    }

    private record CopyAudioContext(String filePath, String codecName) {
    }

    private record PassRequestContext(String inputPath, String directoryName) {
    }

    /**
     * @param externalSrtUrl tokenized download URL of an {@code EXTERNAL_SUBTITLE} stream when the
     *                       media file lives on another node; null when local or not external.
     */
    private record BitmapSubtitleContext(String mediaFilePath, List<MediaFileStreamEntity> streams) {
    }

    private record SubtitleContext(MediaFileStreamEntity subtitleStream, String mediaFilePath, String externalSrtUrl,
                                   ObjectStore externalSrtStore) {
    }
}
