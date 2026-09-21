package app.ister.transcoder;

import lombok.Getter;

@Getter
public enum VideoQuality {
    COPY("copy", null, null, null, null),
    Q720P("720p", "1280:720", "libx264", "2000k", 720),
    Q480P("480p", "854:480", "libx264", "1000k", 480);

    private final String label;
    private final String scale;
    private final String codec;
    private final String bitrate;
    /** Output height, used to honour a user's quality cap. Null for COPY: it keeps the source height. */
    private final Integer height;

    VideoQuality(String label, String scale, String codec, String bitrate, Integer height) {
        this.label = label;
        this.scale = scale;
        this.codec = codec;
        this.bitrate = bitrate;
        this.height = height;
    }

    /**
     * The VBV ceiling ({@code -maxrate} and {@code -bufsize}): twice the target. Without
     * one, {@code -b:v} is only an average — x264 spent 16 Mbit/s on the opening
     * seconds of a "2000k" stream, eight times what the master playlist advertises,
     * and a browser on a modest link stalls on exactly such a segment while hls.js'
     * bandwidth estimate said the rendition would fit. Null for COPY.
     */
    public String getMaxRate() {
        if (bitrate == null) return null;
        return Integer.parseInt(bitrate.substring(0, bitrate.length() - 1)) * 2 + "k";
    }

    public static VideoQuality fromLabel(String label) {
        for (VideoQuality q : values()) {
            if (q.label.equals(label)) return q;
        }
        throw new IllegalArgumentException("Unknown video quality: " + label);
    }

    public AudioQuality getAudioQuality() {
        return switch (this) {
            case Q720P -> AudioQuality.Q192K;
            case Q480P -> AudioQuality.Q64K;
            case COPY -> AudioQuality.COPY;
        };
    }
}
