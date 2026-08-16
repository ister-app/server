package app.ister.disk.events.mediafilefound;

import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.NullOutput;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects baked-in black bars (letterbox/pillarbox inside the frame, common in
 * DVD rips) with ffmpeg's cropdetect filter. Samples a handful of moments
 * spread over the file and combines them conservatively: the union of the
 * detected picture areas, so a dark scene that over-crops is discarded. The
 * result is a crop rect in source pixels; the player applies it at render
 * time (mpv video-crop), which also covers the direct-play variant that the
 * transcoder cannot filter.
 */
@Component
@Slf4j
public class MediaFileFoundDetectCrop {

    /** Sample positions as fractions of the file duration. */
    private static final double[] SAMPLE_POSITIONS = {0.10, 0.30, 0.50, 0.70, 0.90};

    /** Frames decoded per sample; cropdetect converges within a few dozen. */
    private static final int FRAMES_PER_SAMPLE = 60;

    /** Bars smaller than this fraction of a dimension are not worth cropping. */
    static final double MIN_BAR_FRACTION = 0.03;

    /** A crop removing more than this fraction is a misdetection (dark file). */
    static final double MAX_BAR_FRACTION = 0.40;

    private static final Pattern CROP_PATTERN =
            Pattern.compile("crop=(\\d+):(\\d+):(\\d+):(\\d+)");

    public record CropRect(int x, int y, int w, int h) {}

    /**
     * Returns the detected crop rect, the full frame when no (meaningful) bars
     * were found, or empty when detection failed (columns stay null so the
     * scanner's backfill retries on a later run).
     */
    public Optional<CropRect> detectCrop(Path mediaFilePath, String dirOfFFmpeg,
                                         long durationMs, int width, int height) {
        if (durationMs <= 0 || width <= 0 || height <= 0) {
            return Optional.empty();
        }
        List<CropRect> samples = new ArrayList<>();
        for (double position : SAMPLE_POSITIONS) {
            long atMs = (long) (durationMs * position);
            try {
                List<String> cropLines = new ArrayList<>();
                FFmpeg.atPath(Path.of(dirOfFFmpeg))
                        .addInput(UrlInput.fromPath(mediaFilePath)
                                .addArguments("-ss", atMs + "ms"))
                        // NullOutput(false): the default adds -c copy, and
                        // ffmpeg refuses to combine streamcopy with a filter.
                        .addOutput(new NullOutput(false)
                                .addArguments("-map", "0:v:0")
                                .addArguments("-vf", "cropdetect=limit=24:round=2:reset=0")
                                .addArguments("-frames:v", String.valueOf(FRAMES_PER_SAMPLE)))
                        // cropdetect reports at info level; ERROR would silence it.
                        .setLogLevel(LogLevel.INFO)
                        .setOutputListener(line -> {
                            if (line.contains("crop=")) {
                                cropLines.add(line);
                            }
                        })
                        .execute();
                // cropdetect converges — the last line of a sample is the best.
                if (!cropLines.isEmpty()) {
                    parseCropLine(cropLines.get(cropLines.size() - 1)).ifPresent(samples::add);
                }
            } catch (Exception e) {
                log.warn("cropdetect sample at {}ms failed for {}: {}", atMs, mediaFilePath, e.getMessage());
            }
        }
        if (samples.size() < 2) {
            log.warn("cropdetect produced {} usable sample(s) for {} — leaving crop undetected",
                    samples.size(), mediaFilePath);
            return Optional.empty();
        }
        CropRect result = finalizeCrop(union(samples), width, height);
        log.info("cropdetect for {}: {} (frame {}x{})", mediaFilePath, result, width, height);
        return Optional.of(result);
    }

    static Optional<CropRect> parseCropLine(String line) {
        Matcher m = CROP_PATTERN.matcher(line);
        if (!m.find()) return Optional.empty();
        return Optional.of(new CropRect(
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2))));
    }

    /** Union of the picture areas: keeps everything any sample considered picture. */
    static CropRect union(List<CropRect> samples) {
        int x = Integer.MAX_VALUE;
        int y = Integer.MAX_VALUE;
        int right = 0;
        int bottom = 0;
        for (CropRect s : samples) {
            x = Math.min(x, s.x());
            y = Math.min(y, s.y());
            right = Math.max(right, s.x() + s.w());
            bottom = Math.max(bottom, s.y() + s.h());
        }
        return new CropRect(x, y, right - x, bottom - y);
    }

    /**
     * Clamps to the frame, rounds to even coordinates, and falls back to the
     * full frame when the bars are too small to matter or implausibly large.
     */
    static CropRect finalizeCrop(CropRect crop, int width, int height) {
        CropRect fullFrame = new CropRect(0, 0, width, height);
        int x = Math.max(0, crop.x() - (crop.x() % 2));
        int y = Math.max(0, crop.y() - (crop.y() % 2));
        int w = Math.min(width - x, crop.w() + (crop.w() % 2));
        int h = Math.min(height - y, crop.h() + (crop.h() % 2));
        if (w <= 0 || h <= 0) return fullFrame;
        boolean meaningfulHorizontal = width - w >= MIN_BAR_FRACTION * width;
        boolean meaningfulVertical = height - h >= MIN_BAR_FRACTION * height;
        if (!meaningfulHorizontal && !meaningfulVertical) return fullFrame;
        if (width - w > MAX_BAR_FRACTION * width || height - h > MAX_BAR_FRACTION * height) {
            return fullFrame;
        }
        return new CropRect(x, y, w, h);
    }
}
