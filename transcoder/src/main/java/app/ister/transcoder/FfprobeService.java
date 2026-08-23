package app.ister.transcoder;

import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * What one scan of the video packets tells us: the cut grid, and where the
     * stream really ends.
     *
     * @param cutCandidates keyframe pts_time values at least 2 seconds apart —
     *                      the grid segments are cut on
     * @param lastKeyframe  the last keyframe of the stream, <b>unfiltered</b>. An
     *                      MPEG-TS stream copy ends here: it drops the final GOP,
     *                      so a cut at or past this point yields no segment at all.
     *                      Taking this from the filtered list would put it too
     *                      early whenever the final keyframe sits less than two
     *                      seconds after the previous one.
     * @param lastPacketEnd end of the last video packet — where a re-encode ends
     */
    public record VideoTiming(List<Double> cutCandidates, double lastKeyframe, double lastPacketEnd) {
    }

    /**
     * Returns keyframe pts_time values (seconds) for the first video stream,
     * filtered so consecutive keyframes are at least 2 seconds apart.
     * This mirrors the awk filter in test/make_hls.sh.
     */
    public List<Double> getKeyframes(String filePath) {
        return getVideoTiming(filePath).cutCandidates();
    }

    /**
     * Scans the video packets once and reports both the cut grid and the two end
     * timestamps a segment pass can reach. One scan, three answers — the packets
     * were being read anyway.
     */
    public VideoTiming getVideoTiming(String filePath) {
        FFprobeResult result = jaffree.getFFPROBE()
                .setShowPackets(true)
                .setSelectStreams("v:0")
                .setInput(filePath)
                .setLogLevel(LogLevel.ERROR)
                .execute();

        List<Double> keyframes = new ArrayList<>();
        double lastPts = Double.NEGATIVE_INFINITY;
        double lastKeyframe = Double.NaN;
        double lastPacketEnd = Double.NaN;

        for (var packet : result.getPackets()) {
            String flags = packet.getFlags();
            Float ptsTime = packet.getPtsTime();
            if (ptsTime == null) continue;
            double pts = ptsTime;
            Float durationTime = packet.getDurationTime();
            double end = durationTime != null ? pts + durationTime : pts;
            if (Double.isNaN(lastPacketEnd) || end > lastPacketEnd) lastPacketEnd = end;
            if (flags != null && flags.contains("K")) {
                lastKeyframe = pts;
                if (keyframes.isEmpty() || pts - lastPts >= 2.0) {
                    keyframes.add(pts);
                    lastPts = pts;
                }
            }
        }

        log.debug("Found {} keyframes in {} (last keyframe {}, last packet ends {})",
                keyframes.size(), filePath, lastKeyframe, lastPacketEnd);
        return new VideoTiming(keyframes, lastKeyframe, lastPacketEnd);
    }

    /**
     * End of the last packet of {@code selectStreams} (an ffprobe stream specifier
     * such as {@code a:0}), or {@link Double#NaN} when it cannot be determined.
     * <p>
     * Audio commonly ends before the container does, and the cut grid comes from
     * the <i>video</i> keyframes — so without this a playlist advertises an audio
     * segment past the end of its own stream, which FFmpeg never writes.
     * <p>
     * Only the last 30 seconds are read: {@code -read_intervals} seeks straight
     * there, which keeps this to a fraction of a second even on a 40-minute file.
     * <b>Never call this with an http(s) input</b> — the remote download endpoint
     * serves no ranges, so the seek would degrade into streaming the whole file.
     */
    public double getStreamEnd(String filePath, String selectStreams, double totalDuration) {
        double from = Math.max(0.0, totalDuration - 30.0);
        try {
            FFprobeResult result = jaffree.getFFPROBE()
                    .setShowPackets(true)
                    .setSelectStreams(selectStreams)
                    .setReadIntervals(String.format(Locale.ROOT, "%.3f%%+30", from))
                    .setInput(filePath)
                    .setLogLevel(LogLevel.ERROR)
                    .execute();
            double end = Double.NaN;
            for (var packet : result.getPackets()) {
                Float ptsTime = packet.getPtsTime();
                if (ptsTime == null) continue;
                Float durationTime = packet.getDurationTime();
                double packetEnd = durationTime != null ? ptsTime + durationTime : ptsTime;
                if (Double.isNaN(end) || packetEnd > end) end = packetEnd;
            }
            log.debug("Stream {} of {} ends at {}", selectStreams, filePath, end);
            return end;
        } catch (Exception e) {
            // Falling back to the container duration only costs the accuracy this
            // probe was meant to add; failing the playlist would cost playback.
            log.warn("Could not probe the end of stream {} in {}: {}", selectStreams, filePath, e.toString());
            return Double.NaN;
        }
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
