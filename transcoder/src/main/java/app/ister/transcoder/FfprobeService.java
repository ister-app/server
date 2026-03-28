package app.ister.transcoder;

import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps ffprobe operations needed for on-demand HLS segmentation.
 * The keyframe detection logic mirrors test/make_hls.sh.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FfprobeService {

    private final Jaffree jaffree;

    /**
     * Returns keyframe pts_time values (seconds) for the first video stream,
     * filtered so consecutive keyframes are at least 2 seconds apart.
     * This mirrors the awk filter in test/make_hls.sh.
     */
    public List<Double> getKeyframes(String filePath) {
        FFprobeResult result = jaffree.getFFPROBE()
                .setShowPackets(true)
                .setSelectStreams("v:0")
                .setInput(filePath)
                .setLogLevel(LogLevel.ERROR)
                .execute();

        List<Double> keyframes = new ArrayList<>();
        double lastPts = Double.NEGATIVE_INFINITY;

        for (var packet : result.getPackets()) {
            String flags = packet.getFlags();
            Float ptsTime = packet.getPtsTime();
            if (flags != null && flags.contains("K") && ptsTime != null) {
                double pts = ptsTime;
                if (keyframes.isEmpty() || pts - lastPts >= 2.0) {
                    keyframes.add(pts);
                    lastPts = pts;
                }
            }
        }

        log.debug("Found {} keyframes in {}", keyframes.size(), filePath);
        return keyframes;
    }

    /**
     * Returns total duration of the media file in seconds.
     */
    public double getTotalDuration(String filePath) {
        FFprobeResult result = jaffree.getFFPROBE()
                .setShowFormat(true)
                .setInput(filePath)
                .setLogLevel(LogLevel.ERROR)
                .execute();

        Float duration = result.getFormat().getDuration();
        return duration != null ? duration : 0.0;
    }

}
