package app.ister.transcoder;

import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles all subtitle operations: SRT parsing, WebVTT segment generation,
 * and extraction of embedded subtitle streams via FFmpeg.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HlsSubtitleService {

    /** MPEGTS ticks at 90 000 Hz; baked into subtitle timestamps for both VTT and SRT. */
    static final long SUBTITLE_OFFSET_MS = 133200L * 1000 / 90000; // 1480 ms

    private final Jaffree jaffree;
    private final FfprobeService ffprobeService;

    private record SrtCue(long startMs, long endMs, String text) {}

    /**
     * Generates all WebVTT subtitle segment files for the given stream.
     * Segments are written into {@code cacheDir} using the filename pattern
     * {@code seg_sub_{subtitleId}_{%05d}.vtt}.
     */
    void generateSubtitleSegments(MediaFileStreamEntity stream, String mediaFilePath,
                                   UUID mediaFileId, Path cacheDir) throws IOException {
        String srtPath;
        if (stream.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE) {
            srtPath = stream.getPath();
        } else {
            srtPath = extractEmbeddedSubtitleToSrt(stream, mediaFilePath, cacheDir);
        }

        log.debug("Generating subtitle segments from SRT: mediaFileId={} subtitleId={}", mediaFileId, stream.getId());
        List<Double> keyframes = ffprobeService.getKeyframes(mediaFilePath);
        double totalDuration = ffprobeService.getTotalDuration(mediaFilePath);
        List<SrtCue> cues = parseSrt(srtPath);
        writeVttSegments(cues, keyframes, totalDuration, cacheDir, stream.getId());
    }

    /**
     * Extracts an embedded subtitle stream to a single SRT file in {@code cacheDir}.
     * Returns the path of the SRT file. The result is cached — subsequent calls
     * with the same stream return the existing file without re-running FFmpeg.
     */
    String extractEmbeddedSubtitleToSrt(MediaFileStreamEntity stream, String mediaFilePath,
                                          Path cacheDir) throws IOException {
        Path srtFile = cacheDir.resolve("sub_" + stream.getId() + ".srt");
        if (Files.exists(srtFile)) {
            return srtFile.toString();
        }
        Files.createDirectories(srtFile.getParent());
        log.debug("Extracting embedded subtitle to SRT: streamIndex={}", stream.getStreamIndex());
        jaffree.getFFMPEG()
                .addInput(UrlInput.fromUrl(mediaFilePath))
                .addOutput(UrlOutput.toPath(srtFile)
                        .addArguments("-map", "0:" + stream.getStreamIndex())
                        .addArguments("-c:s", "srt"))
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
        return srtFile.toString();
    }

    /**
     * Writes a cached SRT file with all timestamps shifted by {@link #SUBTITLE_OFFSET_MS}.
     * A no-op if the output file already exists.
     */
    void writeSrtWithOffset(String sourceSrtPath, Path outputPath) throws IOException {
        if (Files.exists(outputPath)) return;
        List<SrtCue> cues = parseSrt(sourceSrtPath);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            SrtCue cue = cues.get(i);
            sb.append(i + 1).append("\n");
            sb.append(formatSrtTime(cue.startMs() + SUBTITLE_OFFSET_MS))
                    .append(" --> ")
                    .append(formatSrtTime(cue.endMs() + SUBTITLE_OFFSET_MS))
                    .append("\n");
            sb.append(cue.text()).append("\n\n");
        }
        Files.writeString(outputPath, sb.toString());
    }

    /** Parses an SRT file into a list of cues. Tolerates Windows line endings and missing cue numbers. */
    private List<SrtCue> parseSrt(String srtPath) throws IOException {
        List<SrtCue> cues = new ArrayList<>();
        String[] lines = Files.readString(Path.of(srtPath))
                .replace("\r\n", "\n").replace("\r", "\n").split("\n");

        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.matches("\\d+")) {
                i++;
                continue;
            }
            if (line.contains("-->")) {
                i = parseSrtCue(line, lines, i + 1, cues);
            } else {
                i++;
            }
        }
        return cues;
    }

    /** Parses a single SRT cue (timestamp line already read); returns the new line index. */
    private int parseSrtCue(String timestampLine, String[] lines, int i, List<SrtCue> cues) {
        long[] times = parseSrtTimestampLine(timestampLine);
        if (times.length == 0) return i;
        StringBuilder text = new StringBuilder();
        while (i < lines.length && !lines[i].trim().isEmpty()) {
            if (!text.isEmpty()) text.append("\n");
            text.append(lines[i]);
            i++;
        }
        if (!text.isEmpty()) {
            cues.add(new SrtCue(times[0], times[1], text.toString()));
        }
        return i;
    }

    /** Parses a SRT timestamp line {@code HH:MM:SS,mmm --> HH:MM:SS,mmm} into [startMs, endMs]. */
    private long[] parseSrtTimestampLine(String line) {
        String[] parts = line.split("-->");
        if (parts.length != 2) return new long[0];
        long start = parseSrtTime(parts[0].trim());
        long end = parseSrtTime(parts[1].trim());
        if (start < 0 || end < 0) return new long[0];
        return new long[]{start, end};
    }

    /** Parses {@code HH:MM:SS,mmm} or {@code HH:MM:SS.mmm} into milliseconds. */
    private long parseSrtTime(String s) {
        try {
            s = s.replace(',', '.');
            String[] colonParts = s.split(":");
            int h = Integer.parseInt(colonParts[0].trim());
            int m = Integer.parseInt(colonParts[1].trim());
            String secMs = colonParts[2].trim();
            int dotIdx = secMs.indexOf('.');
            int sec;
            int ms;
            if (dotIdx >= 0) {
                sec = Integer.parseInt(secMs.substring(0, dotIdx));
                StringBuilder msPart = new StringBuilder(secMs.substring(dotIdx + 1));
                while (msPart.length() < 3) msPart.append("0");
                ms = Integer.parseInt(msPart.substring(0, 3));
            } else {
                sec = Integer.parseInt(secMs);
                ms = 0;
            }
            return ((long) h * 3600 + m * 60 + sec) * 1000L + ms;
        } catch (Exception _) {
            return -1;
        }
    }

    /**
     * Writes one WebVTT segment file per keyframe-based time window into {@code cacheDir}.
     * Segments with no cues get a header-only file (required to keep the playlist sequence intact).
     */
    private void writeVttSegments(List<SrtCue> cues, List<Double> keyframes, double totalDuration,
                                   Path cacheDir, UUID subtitleId) throws IOException {
        for (int i = 0; i < keyframes.size(); i++) {
            long segStartMs = (long) (keyframes.get(i) * 1000);
            long segEndMs = (long) ((i + 1 < keyframes.size() ? keyframes.get(i + 1) : totalDuration) * 1000);

            StringBuilder vtt = new StringBuilder();
            vtt.append("WEBVTT\n");

            for (SrtCue cue : cues) {
                if (cue.endMs() > segStartMs && cue.startMs() < segEndMs) {
                    vtt.append("\n");
                    vtt.append(formatVttTime(cue.startMs() + SUBTITLE_OFFSET_MS))
                            .append(" --> ")
                            .append(formatVttTime(cue.endMs() + SUBTITLE_OFFSET_MS))
                            .append("\n");
                    vtt.append(cue.text()).append("\n");
                }
            }

            Path segFile = cacheDir.resolve(
                    String.format(Locale.ROOT, "seg_sub_%s_%05d.vtt", subtitleId, i));
            Files.writeString(segFile, vtt.toString());
        }
    }

    /** Formats milliseconds as {@code HH:MM:SS.mmm} for WebVTT. */
    private String formatVttTime(long ms) {
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        long s = (ms % 60_000) / 1000;
        long millis = ms % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, millis);
    }

    /** Formats milliseconds as {@code HH:MM:SS,mmm} for SRT. */
    private String formatSrtTime(long ms) {
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        long s = (ms % 60_000) / 1000;
        long millis = ms % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, millis);
    }
}
