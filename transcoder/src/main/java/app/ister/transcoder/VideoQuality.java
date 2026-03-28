package app.ister.transcoder;

import lombok.Getter;

@Getter
public enum VideoQuality {
    COPY("copy", null, null, null),
    Q720P("720p", "1280:720", "libx264", "2000k"),
    Q480P("480p", "854:480", "libx264", "1000k");

    private final String label;
    private final String scale;
    private final String codec;
    private final String bitrate;

    VideoQuality(String label, String scale, String codec, String bitrate) {
        this.label = label;
        this.scale = scale;
        this.codec = codec;
        this.bitrate = bitrate;
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
