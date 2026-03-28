package app.ister.transcoder;

import lombok.Getter;

@Getter
public enum AudioQuality {
    COPY("copy", null),
    Q192K("192k", "192k"),
    Q64K("64k", "64k");

    private final String label;
    /** AAC bitrate, null for stream copy. */
    private final String bitrate;

    AudioQuality(String label, String bitrate) {
        this.label = label;
        this.bitrate = bitrate;
    }

    public static AudioQuality fromLabel(String label) {
        for (AudioQuality q : values()) {
            if (q.label.equals(label)) return q;
        }
        throw new IllegalArgumentException("Unknown audio quality: " + label);
    }
}
