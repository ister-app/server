package app.ister.transcoder;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Stateless on-demand HLS service — coordinates playlist building, transcoding, and subtitle handling.
 * <p>
 * All generated files are cached under {@code tmpDir/{mediaFileId}/} and reused on
 * subsequent requests (last-modified time is touched on each access).
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

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    /** Per-subtitle locks to prevent duplicate segment generation for the same subtitle stream. */
    private final ConcurrentHashMap<String, Object> subtitleLocks = new ConcurrentHashMap<>();

    // ========== Public API ==========

    /**
     * Returns (cached) master.m3u8 content.
     * On first generation, all stream playlists are pre-generated using a single ffprobe call.
     *
     * @param direct    include the stream-copy (direct) video + audio-copy quality variant
     * @param transcode include the re-encoded (720p + 480p) video quality variants
     */
    @Transactional(readOnly = true)
    public String getMasterPlaylist(UUID mediaFileId, boolean direct, boolean transcode, SubtitleFormat subtitleFormat) throws IOException {
        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();
        String cacheFilename = String.format(Locale.ROOT, "master_d%d_t%d_s%s.m3u8",
                direct ? 1 : 0, transcode ? 1 : 0, subtitleFormat.name());
        Path cacheFile = cacheDir(mediaFileId).resolve(cacheFilename);

        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readString(cacheFile);
        }

        Files.createDirectories(cacheFile.getParent());
        String masterContent = playlistBuilder.buildMasterPlaylist(mediaFile, direct, transcode, subtitleFormat);
        Files.writeString(cacheFile, masterContent);

        // Pre-generate all stream playlists with a single ffprobe call
        preGenerateStreamPlaylists(mediaFile, mediaFileId, direct, transcode, subtitleFormat);

        return masterContent;
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
        String filePath = getMediaFilePath(mediaFileId);
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
     * format: {@code seg_video_{quality}_%05d.ts} — produced by a background FFmpeg pass.
     */
    public Path getVideoSegment(UUID mediaFileId, String segmentFilename) throws IOException {
        String filePath = getMediaFilePath(mediaFileId);
        String[] parts = segmentFilename.replace(".ts", "").split("_");
        Path cacheFile = cacheDir(mediaFileId).resolve(segmentFilename);

        Path stable = transcodeService.stableSegmentOrNull(cacheFile);
        if (stable != null) return stable;
        Files.createDirectories(cacheFile.getParent());
        String qualityLabel = parts[2];
        VideoQuality quality = VideoQuality.fromLabel(qualityLabel);
        String generationKey = mediaFileId + "_video_" + qualityLabel;
        Path cacheDirPath = cacheDir(mediaFileId);
        transcodeService.ensurePassStarted(generationKey,
                () -> transcodeService.startVideoPass(filePath, cacheDirPath, quality));
        return transcodeService.waitForSegment(cacheFile, generationKey);
    }

    /**
     * Returns path to (cached) audio-only .ts segment.
     * <p>
     * COPY format:       {@code seg_audio_{start}_{duration}_{streamIdx}_copy.ts} — generated on demand per segment.
     * Transcoded format: {@code seg_audio_{streamIdx}_{bitrate}_%05d.ts}           — produced by a background FFmpeg pass.
     */
    public Path getAudioSegment(UUID mediaFileId, String segmentFilename) throws IOException {
        String filePath = getMediaFilePath(mediaFileId);
        String[] parts = segmentFilename.replace(".ts", "").split("_");
        Path cacheFile = cacheDir(mediaFileId).resolve(segmentFilename);

        if ("copy".equals(parts[parts.length - 1])) {
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
        AudioQuality audioQuality = AudioQuality.fromLabel(bitrateLabel);
        String generationKey = mediaFileId + "_audio_" + streamIdx + "_" + bitrateLabel;
        Path cacheDirPath = cacheDir(mediaFileId);
        transcodeService.ensurePassStarted(generationKey,
                () -> transcodeService.startAudioPass(filePath, cacheDirPath, streamIdx, audioQuality));
        return transcodeService.waitForSegment(cacheFile, generationKey);
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
            return Files.readString(cacheFile);
        }
        Files.createDirectories(cacheFile.getParent());

        String mediaFilePath = getMediaFilePath(mediaFileId);
        String generationKey = mediaFileId + "_sub_" + subtitleId;
        Object lock = subtitleLocks.computeIfAbsent(generationKey, k -> new Object());
        synchronized (lock) {
            if (!Files.exists(cacheFile)) {
                subtitleService.generateSubtitleSegments(subtitleStream, mediaFilePath, mediaFileId, cacheDir(mediaFileId));
            }
        }
        return Files.readString(cacheFile);
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
            String mediaFilePath = getMediaFilePath(mediaFileId);
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
        String filePath = mediaFile.getPath();
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

    // ========== Cache helpers ==========

    private Path cacheDir(UUID mediaFileId) {
        return Paths.get(tmpDir, mediaFileId.toString());
    }

    private String getMediaFilePath(UUID mediaFileId) {
        return mediaFileRepository.findById(mediaFileId).orElseThrow().getPath();
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
