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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
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
@RequiredArgsConstructor
public class HlsService {

    private final HlsPlaylistBuilder playlistBuilder;
    private final HlsSubtitleService subtitleService;
    private final HlsTranscodeService transcodeService;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final MessageSender messageSender;
    private final RemoteNodeClient remoteNodeClient;
    private final NodeTokenManager nodeTokenManager;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @Value("${app.ister.server.hls.master-playlist-timeout-ms:120000}")
    private long masterPlaylistTimeoutMs;

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
    @Transactional(readOnly = true)
    public String getMasterPlaylist(UUID mediaFileId, boolean direct, boolean transcode, SubtitleFormat subtitleFormat) throws IOException {
        String cacheFilename = String.format(Locale.ROOT, "master_d%d_t%d_s%s.m3u8",
                direct ? 1 : 0, transcode ? 1 : 0, subtitleFormat.name());
        Path cacheFile = cacheDir(mediaFileId).resolve(cacheFilename);

        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readString(cacheFile);
        }

        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
        String directoryName = mediaFile.getDirectoryEntity().getName();
        log.debug("Master playlist cache miss for {}, sending TRANSCODE_REQUESTED to directory queue {}", mediaFileId, directoryName);

        messageSender.sendTranscodeRequested(
                TranscodeRequestedData.builder()
                        .eventType(EventType.TRANSCODE_REQUESTED)
                        .mediaFileId(mediaFileId)
                        .direct(direct)
                        .transcode(transcode)
                        .subtitleFormat(subtitleFormat)
                        .build(),
                directoryName);

        return waitForMasterPlaylist(cacheFile);
    }

    /**
     * Generates master.m3u8 and all stream playlists and writes them to cache.
     * Called by the {@code HandleTranscodeRequested} event handler.
     * If the media file is on a remote node, all generated playlists are uploaded to that node.
     */
    @Transactional(readOnly = true)
    public void generateAllPlaylists(UUID mediaFileId, boolean direct, boolean transcode, SubtitleFormat subtitleFormat) throws IOException {
        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
        String cacheFilename = String.format(Locale.ROOT, "master_d%d_t%d_s%s.m3u8",
                direct ? 1 : 0, transcode ? 1 : 0, subtitleFormat.name());
        Path cacheFile = cacheDir(mediaFileId).resolve(cacheFilename);

        if (Files.exists(cacheFile)) {
            log.debug("Playlists already cached for {}, skipping generation", mediaFileId);
            return;
        }

        Files.createDirectories(cacheFile.getParent());
        String masterContent = playlistBuilder.buildMasterPlaylist(mediaFile, direct, transcode, subtitleFormat);
        preGenerateStreamPlaylists(mediaFile, mediaFileId, direct, transcode, subtitleFormat);
        // Write master playlist last so that getMasterPlaylist's poll only fires after all stream playlists exist
        Files.writeString(cacheFile, masterContent);
        log.debug("Generated all playlists for {}", mediaFileId);

        if (isRemote(mediaFile)) {
            String nodeUrl = mediaFile.getDirectoryEntity().getNodeEntity().getUrl();
            try (Stream<Path> files = Files.list(cacheDir(mediaFileId))) {
                files.filter(p -> p.toString().endsWith(".m3u8"))
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
        boolean remote = isRemote(mediaFile);
        // Re-resolve from this node's perspective: local path if file is here, HTTP URL if on another node.
        String mediaFilePath = resolveInputPath(mediaFile);

        Path cacheDirPath = cacheDir(data.getMediaFileId());
        String segmentPrefix;
        Runnable passStarter;

        if ("video".equals(data.getPassCategory())) {
            VideoQuality vq = VideoQuality.fromLabel(data.getQualityLabel());
            segmentPrefix = "seg_video_" + data.getQualityLabel() + "_";
            passStarter = () -> transcodeService.startVideoPass(mediaFilePath, cacheDirPath, vq);
        } else {
            AudioQuality aq = AudioQuality.fromLabel(data.getQualityLabel());
            segmentPrefix = "seg_audio_" + data.getAudioStreamIndex() + "_" + data.getQualityLabel() + "_";
            passStarter = () -> transcodeService.startAudioPass(mediaFilePath, cacheDirPath,
                    data.getAudioStreamIndex(), aq);
        }

        transcodeService.ensurePassStarted(data.getPassKey(), passStarter);

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

        for (int i = 0; i < videoQualities.length; i++) {
            if (!includeQuality[i]) continue;
            String qualityLabel = videoQualities[i].getLabel();
            String passKey = mediaFileId + "_video_" + qualityLabel;
            if (!transcodeService.isPassActive(passKey) && !transcodeService.hasCompletedPass(passKey)) {
                startPass(TranscodePassRequestedData.builder()
                        .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                        .mediaFileId(mediaFileId)
                        .passKey(passKey)
                        .mediaFilePath(inputPath)
                        .passCategory("video")
                        .qualityLabel(qualityLabel)
                        .build());
            }
        }

        for (int qi = 0; qi < audioQualities.length; qi++) {
            if (!includeQuality[qi] || audioQualities[qi] == AudioQuality.COPY) continue;
            String qualityLabel = audioQualities[qi].getLabel();
            for (MediaFileStreamEntity audioStream : audioStreams) {
                int streamIdx = audioStream.getStreamIndex();
                String passKey = mediaFileId + "_audio_" + streamIdx + "_" + qualityLabel;
                if (!transcodeService.isPassActive(passKey) && !transcodeService.hasCompletedPass(passKey)) {
                    startPass(TranscodePassRequestedData.builder()
                            .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                            .mediaFileId(mediaFileId)
                            .passKey(passKey)
                            .mediaFilePath(inputPath)
                            .passCategory("audio")
                            .qualityLabel(qualityLabel)
                            .audioStreamIndex(streamIdx)
                            .build());
                }
            }
        }
    }

    /**
     * Returns (cached) stream playlist content.
     * Filename determines the type: stream_video_*, stream_audio_*, or stream_sub_*.
     */
    @Transactional(readOnly = true)
    public String getStreamPlaylist(UUID mediaFileId, String streamFilename) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(streamFilename);
        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readString(cacheFile);
        }
        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
        String filePath = resolveInputPath(mediaFile);
        List<Double> keyframes = transcodeService.getCachedKeyframes(filePath);
        double totalDuration = transcodeService.getTotalDuration(filePath);
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
    @Transactional(readOnly = true)
    public Path getVideoSegment(UUID mediaFileId, String segmentFilename) throws IOException {
        Path cacheFile = cacheDir(mediaFileId).resolve(segmentFilename);

        Path stable = transcodeService.stableSegmentOrNull(cacheFile);
        if (stable != null) return stable;
        Files.createDirectories(cacheFile.getParent());
        String[] parts = segmentFilename.replace(".ts", "").split("_");
        String qualityLabel = parts[2];
        String passKey = mediaFileId + "_video_" + qualityLabel;
        if (!transcodeService.isPassActive(passKey) && !transcodeService.hasCompletedPass(passKey)) {
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            String inputPath = resolveInputPath(mediaFile);
            String directoryName = mediaFile.getDirectoryEntity().getName();
            messageSender.sendTranscodePassRequested(
                    TranscodePassRequestedData.builder()
                            .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                            .mediaFileId(mediaFileId)
                            .passKey(passKey)
                            .mediaFilePath(inputPath)
                            .passCategory("video")
                            .qualityLabel(qualityLabel)
                            .build(),
                    directoryName);
        }
        return transcodeService.waitForSegment(cacheFile, passKey);
    }

    /**
     * Returns path to (cached) audio-only .ts segment.
     * <p>
     * COPY format:       {@code seg_audio_{start}_{duration}_{streamIdx}_copy.ts} — generated on demand per segment.
     * Transcoded format: {@code seg_audio_{streamIdx}_{bitrate}_%05d.ts}           — produced by a background FFmpeg pass.
     * On first request for a transcoded quality, sends a {@code TRANSCODE_PASS_REQUESTED} event.
     */
    @Transactional(readOnly = true)
    public Path getAudioSegment(UUID mediaFileId, String segmentFilename) throws IOException {
        String[] parts = segmentFilename.replace(".ts", "").split("_");
        Path cacheFile = cacheDir(mediaFileId).resolve(segmentFilename);

        if ("copy".equals(parts[parts.length - 1])) {
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            String filePath = resolveInputPath(mediaFile);
            return getCachedOrGenerateBinary(cacheFile, out -> {
                double start = Double.parseDouble(parts[2]);
                double duration = Double.parseDouble(parts[3]);
                int audioIdx = Integer.parseInt(parts[4]);
                transcodeService.generateAudioSegment(filePath, out, start, duration, audioIdx, AudioQuality.COPY);
            });
        }

        // Transcoded: seg_audio_{streamIdx}_{bitrate}_{%05d}.ts
        Path stable = transcodeService.stableSegmentOrNull(cacheFile);
        if (stable != null) return stable;
        Files.createDirectories(cacheFile.getParent());
        int streamIdx = Integer.parseInt(parts[2]);
        String bitrateLabel = parts[3];
        String passKey = mediaFileId + "_audio_" + streamIdx + "_" + bitrateLabel;
        if (!transcodeService.isPassActive(passKey) && !transcodeService.hasCompletedPass(passKey)) {
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            String inputPath = resolveInputPath(mediaFile);
            String directoryName = mediaFile.getDirectoryEntity().getName();
            messageSender.sendTranscodePassRequested(
                    TranscodePassRequestedData.builder()
                            .eventType(EventType.TRANSCODE_PASS_REQUESTED)
                            .mediaFileId(mediaFileId)
                            .passKey(passKey)
                            .mediaFilePath(inputPath)
                            .passCategory("audio")
                            .qualityLabel(bitrateLabel)
                            .audioStreamIndex(streamIdx)
                            .build(),
                    directoryName);
        }
        return transcodeService.waitForSegment(cacheFile, passKey);
    }

    /**
     * Returns (cached) WebVTT subtitle segment content.
     * Filename format: {@code seg_sub_{subtitleStreamEntityId}_{%05d}.vtt}
     */
    @Transactional(readOnly = true)
    public String getSubtitleSegment(UUID mediaFileId, String segmentFilename) throws IOException {
        // Parse: seg_sub_{uuid}_{%05d}.vtt
        String withoutExt = segmentFilename.replace(".vtt", "");
        int lastUnderscore = withoutExt.lastIndexOf('_');
        UUID subtitleId = UUID.fromString(withoutExt.substring("seg_sub_".length(), lastUnderscore));

        MediaFileStreamEntity subtitleStream = mediaFileStreamRepository.findById(subtitleId).orElseThrow();
        Path cacheFile = cacheDir(mediaFileId).resolve(segmentFilename);

        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readString(cacheFile, StandardCharsets.UTF_8);
        }
        Files.createDirectories(cacheFile.getParent());

        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
        String mediaFilePath = resolveInputPath(mediaFile);
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
    @Transactional(readOnly = true)
    public Path getSrtSubtitle(UUID mediaFileId, String filename) throws IOException {
        UUID subtitleId = UUID.fromString(filename.replace("sub_", "").replace(".srt", ""));
        MediaFileStreamEntity stream = mediaFileStreamRepository.findById(subtitleId).orElseThrow();

        String sourceSrtPath;
        if (stream.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE) {
            sourceSrtPath = stream.getPath();
        } else {
            MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
            String mediaFilePath = resolveInputPath(mediaFile);
            Files.createDirectories(cacheDir(mediaFileId));
            sourceSrtPath = subtitleService.extractEmbeddedSubtitleToSrt(stream, mediaFilePath, cacheDir(mediaFileId));
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
        List<Double> keyframes = transcodeService.getCachedKeyframes(filePath);
        double totalDuration = transcodeService.getTotalDuration(filePath);

        List<MediaFileStreamEntity> streams = mediaFile.getMediaFileStreamEntity();
        List<MediaFileStreamEntity> audioStreams = streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.AUDIO)
                .toList();
        List<MediaFileStreamEntity> subtitleStreams = streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE
                        || s.getCodecType() == StreamCodecType.SUBTITLE)
                .toList();

        boolean[] includeVideo = {direct, transcode, transcode};
        VideoQuality[] videoQualities = VideoQuality.values();
        AudioQuality[] audioQualities = AudioQuality.values();

        for (int i = 0; i < videoQualities.length; i++) {
            if (!includeVideo[i]) continue;
            VideoQuality vq = videoQualities[i];
            String filename = "stream_video_" + vq.getLabel() + ".m3u8";
            String qualityLabel = vq.getLabel();
            writeStreamPlaylistIfAbsent(mediaFileId, filename, keyframes, totalDuration,
                    (start, dur, idx) -> String.format(Locale.ROOT, "seg_video_%s_%05d.ts", qualityLabel, idx));
        }

        preGenerateAudioPlaylists(mediaFileId, audioStreams, audioQualities, includeVideo, keyframes, totalDuration);
        preGenerateSubtitlePlaylists(mediaFileId, subtitleStreams, subtitleFormat, keyframes, totalDuration);
    }

    private void preGenerateAudioPlaylists(UUID mediaFileId, List<MediaFileStreamEntity> audioStreams,
                                            AudioQuality[] audioQualities, boolean[] includeVideo,
                                            List<Double> keyframes, double totalDuration) throws IOException {
        for (int qi = 0; qi < audioQualities.length; qi++) {
            if (!includeVideo[qi]) continue;
            AudioQuality aq = audioQualities[qi];
            for (MediaFileStreamEntity as : audioStreams) {
                String filename = String.format(Locale.ROOT, "stream_audio_%d_%s.m3u8", as.getStreamIndex(), aq.getLabel());
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
            String filename = "stream_sub_" + ss.getId() + "_" + formatLabel + ".m3u8";
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
                + "/download?token=" + nodeTokenManager.getToken();
    }

    private void watchAndUpload(Path cacheDirPath, String prefix, String nodeUrl,
                                 UUID mediaFileId, CompletableFuture<Void> passFuture) {
        Set<String> uploaded = new HashSet<>();
        while (!passFuture.isDone() || !allUploaded(cacheDirPath, prefix, uploaded)) {
            try (Stream<Path> files = Files.list(cacheDirPath)) {
                files.filter(p -> p.getFileName().toString().startsWith(prefix)
                               && p.getFileName().toString().endsWith(".ts")
                               && !uploaded.contains(p.getFileName().toString()))
                     .forEach(p -> {
                         try {
                             if (transcodeService.stableSegmentOrNull(p) != null) {
                                 remoteNodeClient.uploadFile(nodeUrl, mediaFileId, p);
                                 uploaded.add(p.getFileName().toString());
                             }
                         } catch (IOException e) {
                             log.warn("Segment upload failed: {}", p, e);
                         }
                     });
                if (!passFuture.isDone()) {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.nio.file.NoSuchFileException e) {
                log.debug("Cache dir removed, stopping watcher: {}", cacheDirPath);
                break;
            } catch (IOException e) {
                log.warn("Watcher error in {}", cacheDirPath, e);
                break;
            }
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
        } catch (IOException e) {
            return false;
        }
    }

    // ========== Polling ==========

    private String waitForMasterPlaylist(Path cacheFile) throws IOException {
        long deadline = System.currentTimeMillis() + masterPlaylistTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(cacheFile)) {
                return Files.readString(cacheFile);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
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

    private Path getCachedOrGenerateBinary(Path cacheFile, Consumer<Path> generator) throws IOException {
        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return cacheFile;
        }
        Files.createDirectories(cacheFile.getParent());
        generator.accept(cacheFile);
        return cacheFile;
    }
}
