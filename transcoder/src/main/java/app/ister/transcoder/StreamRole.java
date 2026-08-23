package app.ister.transcoder;

/**
 * Which stream of a media file a segment grid is meant for.
 * <p>
 * The distinction matters because every role ends somewhere else: an MPEG-TS
 * stream copy of video stops at the last keyframe (it drops the final GOP), a
 * re-encode runs to the last video packet, and an audio stream ends wherever
 * that particular track ends — commonly a second or more before the container
 * does. Advertising a segment past the end of its own stream is what produces a
 * playlist entry FFmpeg never writes.
 */
public record StreamRole(Kind kind, int audioStreamIndex) {

    public enum Kind {
        /** Video, stream-copied into MPEG-TS. */
        VIDEO_COPY,
        /** Video, re-encoded (720p/480p). */
        VIDEO_ENCODE,
        /** One audio rendition, copied or re-encoded — both end with the source track. */
        AUDIO,
        /** Subtitles; segments are written by HlsSubtitleService, not by an FFmpeg pass. */
        SUBTITLE
    }

    public static StreamRole videoCopy() {
        return new StreamRole(Kind.VIDEO_COPY, -1);
    }

    public static StreamRole videoEncode() {
        return new StreamRole(Kind.VIDEO_ENCODE, -1);
    }

    public static StreamRole audio(int streamIndex) {
        return new StreamRole(Kind.AUDIO, streamIndex);
    }

    public static StreamRole subtitle() {
        return new StreamRole(Kind.SUBTITLE, -1);
    }

    /**
     * The ffprobe stream specifier to measure this role's end with, or null when
     * it needs no probe.
     * <p>
     * The index is the file's absolute stream index — the same one the pass maps
     * with {@code -map 0:<idx>}. Writing it as {@code a:<idx>} would select the
     * <i>n-th audio stream</i> instead, which for a normal file (video 0, audio 1)
     * asks for a second audio track that does not exist, silently measures
     * nothing, and leaves the grid untrimmed.
     */
    public String selectStreams() {
        return kind == Kind.AUDIO ? String.valueOf(audioStreamIndex) : null;
    }
}
