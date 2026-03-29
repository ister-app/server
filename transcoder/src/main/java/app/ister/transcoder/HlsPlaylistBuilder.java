package app.ister.transcoder;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.enums.SubtitleFormat;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds M3U8 playlist content — no I/O, no FFmpeg, no database access.
 */
@Component
public class HlsPlaylistBuilder {

    private static final String EXT_M3U8 = ".m3u8";
    private static final String PREFIX_STREAM_SUB = "stream_sub_";
    private static final String PREFIX_STREAM_VIDEO = "stream_video_";

    private static final Set<String> IMAGE_SUBTITLE_CODECS =
            Set.of("dvd_subtitle", "hdmv_pgs_subtitle", "dvb_subtitle");

    @FunctionalInterface
    interface SegmentNamer {
        String name(double start, double duration, int index);
    }

    private boolean isImageSubtitle(MediaFileStreamEntity s) {
        return IMAGE_SUBTITLE_CODECS.contains(s.getCodecName());
    }

    /**
     * Builds the master.m3u8 content for the given media file.
     *
     * @param direct    include the stream-copy (direct) quality variant
     * @param transcode include the re-encoded (720p + 480p) quality variants
     */
    public String buildMasterPlaylist(MediaFileEntity mediaFile, boolean direct, boolean transcode, SubtitleFormat subtitleFormat) {
        List<MediaFileStreamEntity> streams = mediaFile.getMediaFileStreamEntity();

        MediaFileStreamEntity videoStream = streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.VIDEO)
                .findFirst()
                .orElse(null);
        int srcWidth = videoStream != null ? videoStream.getWidth() : 1920;
        int srcHeight = videoStream != null ? videoStream.getHeight() : 1080;

        List<MediaFileStreamEntity> audioStreams = streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.AUDIO)
                .toList();

        List<MediaFileStreamEntity> subtitleStreams = streams.stream()
                .filter(s -> (s.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE
                        || s.getCodecType() == StreamCodecType.SUBTITLE)
                        && !isImageSubtitle(s))
                .toList();

        // Index mapping: 0=COPY/direct, 1=720p/transcode, 2=480p/transcode
        boolean[] includeVideo = {direct, transcode, transcode};
        AudioQuality[] audioQualities = AudioQuality.values();
        VideoQuality[] videoQualities = VideoQuality.values();
        long[] bandwidths = {8_000_000, 2_000_000, 1_000_000};
        int[] resWidths = {srcWidth, 1280, 854};
        int[] resHeights = {srcHeight, 720, 480};

        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:6\n");
        sb.append("\n");

        appendAudioMediaEntries(sb, audioStreams, audioQualities, includeVideo);
        appendSubtitleMediaEntries(sb, subtitleStreams, subtitleFormat);
        appendVideoVariants(sb, videoQualities, audioQualities, includeVideo, bandwidths, resWidths, resHeights, subtitleStreams);

        return sb.toString();
    }

    private void appendAudioMediaEntries(StringBuilder sb, List<MediaFileStreamEntity> audioStreams,
                                          AudioQuality[] audioQualities, boolean[] includeVideo) {
        // Audio EXT-X-MEDIA: transcoded variants (720p and 480p) share a single audio group
        // so that ABR switching does not reset hls.js's audio SourceBuffer.
        boolean firstGroup = true;
        boolean transcodedAudioEmitted = false;
        for (int qi = 0; qi < audioQualities.length; qi++) {
            AudioQuality aq = audioQualities[qi];
            boolean isTranscoded = (aq == AudioQuality.Q192K || aq == AudioQuality.Q64K);
            if (!includeVideo[qi] || (isTranscoded && transcodedAudioEmitted)) continue;
            AudioQuality emitAq = isTranscoded ? AudioQuality.Q192K : aq;
            String groupId = "audio-" + emitAq.getLabel();
            for (int ai = 0; ai < audioStreams.size(); ai++) {
                MediaFileStreamEntity as = audioStreams.get(ai);
                String lang = as.getLanguage() != null ? as.getLanguage() : "und";
                String name = as.getTitle() != null ? as.getTitle() : lang;
                boolean isDefault = firstGroup && (ai == 0);
                sb.append(String.format(
                        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"%s\",LANGUAGE=\"%s\",NAME=\"%s\",DEFAULT=%s,AUTOSELECT=YES,URI=\"stream_audio_%d_%s" + EXT_M3U8 + "\"%n",
                        groupId, lang, name, isDefault ? "YES" : "NO",
                        as.getStreamIndex(), emitAq.getLabel()));
            }
            firstGroup = false;
            if (isTranscoded) transcodedAudioEmitted = true;
        }
    }

    private void appendSubtitleMediaEntries(StringBuilder sb, List<MediaFileStreamEntity> subtitleStreams,
                                             SubtitleFormat subtitleFormat) {
        // Subtitle tracks (text-based only — image-based subtitles are skipped)
        if (subtitleStreams.isEmpty()) return;
        String formatLabel = subtitleFormat.name().toLowerCase();
        sb.append("\n");
        for (int i = 0; i < subtitleStreams.size(); i++) {
            MediaFileStreamEntity ss = subtitleStreams.get(i);
            String lang = ss.getLanguage() != null ? ss.getLanguage() : "und";
            String name = ss.getTitle() != null ? ss.getTitle() : lang;
            String subPlaylist = PREFIX_STREAM_SUB + ss.getId() + "_" + formatLabel + EXT_M3U8;
            sb.append(String.format(
                    "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",LANGUAGE=\"%s\",NAME=\"%s\",DEFAULT=NO,AUTOSELECT=%s,FORCED=NO,URI=\"%s\"%n",
                    lang, name, i == 0 ? "YES" : "NO", subPlaylist));
        }
    }

    private void appendVideoVariants(StringBuilder sb, VideoQuality[] videoQualities, AudioQuality[] audioQualities,
                                      boolean[] includeVideo, long[] bandwidths, int[] resWidths, int[] resHeights,
                                      List<MediaFileStreamEntity> subtitleStreams) {
        // Video quality variants
        String subtitleAttr = subtitleStreams.isEmpty() ? "" : ",SUBTITLES=\"subs\"";
        sb.append("\n");
        for (int i = 0; i < videoQualities.length; i++) {
            if (!includeVideo[i]) continue;
            AudioQuality variantAq = audioQualities[i];
            AudioQuality resolvedAq = (variantAq == AudioQuality.Q64K) ? AudioQuality.Q192K : variantAq;
            String audioGroup = "audio-" + resolvedAq.getLabel();
            sb.append(String.format(
                    "#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%dx%d,CODECS=\"avc1.640028,mp4a.40.2\",AUDIO=\"%s\"%s%n",
                    bandwidths[i], resWidths[i], resHeights[i], audioGroup, subtitleAttr));
            sb.append(PREFIX_STREAM_VIDEO).append(videoQualities[i].getLabel()).append(EXT_M3U8).append("\n");
        }
    }

    /**
     * Builds stream playlist content. Filename determines the type:
     * {@code stream_video_*}, {@code stream_audio_*}, or {@code stream_sub_*}.
     */
    public String buildStreamPlaylist(String streamFilename, List<Double> keyframes, double totalDuration) {
        if (streamFilename.startsWith(PREFIX_STREAM_VIDEO)) {
            String quality = streamFilename.replace(PREFIX_STREAM_VIDEO, "").replace(EXT_M3U8, "");
            return buildVodPlaylist(keyframes, totalDuration,
                    (start, dur, idx) -> String.format(Locale.ROOT, "seg_video_%s_%05d.ts", quality, idx));
        }
        if (streamFilename.startsWith("stream_audio_")) {
            String part = streamFilename.replace("stream_audio_", "").replace(EXT_M3U8, "");
            int sep = part.lastIndexOf('_');
            int audioIdx = Integer.parseInt(part.substring(0, sep));
            String bitrate = part.substring(sep + 1);
            if ("copy".equals(bitrate)) {
                return buildSingleSegmentPlaylist(totalDuration,
                        String.format(Locale.ROOT, "seg_audio_0.000000_%.6f_%d_copy.ts", totalDuration, audioIdx));
            }
            return buildVodPlaylist(keyframes, totalDuration,
                    (start, dur, idx) -> String.format(Locale.ROOT, "seg_audio_%d_%s_%05d.ts", audioIdx, bitrate, idx));
        }
        if (streamFilename.startsWith(PREFIX_STREAM_SUB)) {
            if (streamFilename.endsWith("_srt" + EXT_M3U8)) {
                String subtitleId = streamFilename.replace(PREFIX_STREAM_SUB, "").replace("_srt" + EXT_M3U8, "");
                return buildSingleSegmentPlaylist(totalDuration, "sub_" + subtitleId + ".srt");
            }
            // _webvtt.m3u8
            String subtitleId = streamFilename.replace(PREFIX_STREAM_SUB, "").replace("_webvtt" + EXT_M3U8, "");
            return buildVodPlaylist(keyframes, totalDuration,
                    (start, dur, idx) -> String.format(Locale.ROOT, "seg_sub_%s_%05d.vtt", subtitleId, idx));
        }
        throw new IllegalArgumentException("Unknown stream filename: " + streamFilename);
    }

    String buildVodPlaylist(List<Double> keyframes, double totalDuration, SegmentNamer namer) {
        if (keyframes.isEmpty()) {
            throw new IllegalStateException("No keyframes found");
        }

        int numSegs = keyframes.size();
        double[] durations = new double[numSegs];
        double maxDuration = 0;

        for (int i = 0; i < numSegs; i++) {
            double start = keyframes.get(i);
            double dur = (i + 1 < numSegs)
                    ? keyframes.get(i + 1) - start
                    : totalDuration - start;
            durations[i] = dur;
            if (dur > maxDuration) maxDuration = dur;
        }

        int targetDuration = (int) Math.ceil(maxDuration);

        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:6\n");
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration).append("\n");
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n");
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
        sb.append("\n");

        for (int i = 0; i < numSegs; i++) {
            sb.append(String.format(Locale.ROOT, "#EXTINF:%.6f,%n", durations[i]));
            sb.append(namer.name(keyframes.get(i), durations[i], i)).append("\n");
        }
        sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();
    }

    String buildSingleSegmentPlaylist(double totalDuration, String segmentFilename) {
        int targetDuration = (int) Math.ceil(totalDuration);
        return "#EXTM3U\n" +
                "#EXT-X-VERSION:6\n" +
                "#EXT-X-TARGETDURATION:" + targetDuration + "\n" +
                "#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXT-X-PLAYLIST-TYPE:VOD\n" +
                "\n" +
                String.format(Locale.ROOT, "#EXTINF:%.6f,%n", totalDuration) +
                segmentFilename + "\n" +
                "#EXT-X-ENDLIST\n";
    }
}
