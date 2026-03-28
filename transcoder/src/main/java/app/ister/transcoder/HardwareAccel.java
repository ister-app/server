package app.ister.transcoder;

/**
 * Describes a hardware acceleration backend for FFmpeg video transcoding.
 * <p>
 * Each constant encapsulates the four things that differ per backend:
 * <ol>
 *   <li>{@link #inputArgs(String)}  — arguments added to the UrlInput <em>before</em> {@code -i}
 *   <li>{@link #encoder()}          — {@code -c:v} value (null = keep quality's software codec)
 *   <li>{@link #scaleFilter(String)} — {@code -vf} expression
 *   <li>{@link #preset()}           — {@code -preset} value (null = omit -preset entirely)
 * </ol>
 * <p>
 * VAAPI uses <em>software decode + hardware encode</em>: {@code -vaapi_device} is placed
 * before {@code -i} (via {@link #inputArgs}) so FFmpeg initialises the VAAPI device context
 * before reading the input. Decoding always happens in software, which supports any input
 * codec (H.264, HEVC, MPEG-2, …). The {@code scale=…,format=nv12,hwupload} filter then
 * uploads CPU frames to a VAAPI surface for hardware encoding.
 */
public enum HardwareAccel {

    NONE {
        @Override public String[] inputArgs(String device) { return new String[0]; }
        @Override public String encoder()                  { return null; }
        @Override public String scaleFilter(String wh)     { return "scale=" + wh; }
        @Override public String preset()                   { return "ultrafast"; }
    },

    /**
     * Intel/AMD VAAPI: software decode, VAAPI encode.
     * {@code -vaapi_device} must appear before {@code -i}; placing it in {@link #inputArgs}
     * ensures Jaffree inserts it in the correct position.
     */
    VAAPI {
        @Override
        public String[] inputArgs(String device) {
            return new String[]{"-vaapi_device", device};
        }
        @Override public String encoder()              { return "h264_vaapi"; }
        @Override public String scaleFilter(String wh) { return "scale=" + wh + ",format=nv12,hwupload"; }
        @Override public String preset()               { return null; } // h264_vaapi ondersteunt geen -preset
    },

    NVDEC {
        @Override
        public String[] inputArgs(String device) {
            return new String[]{"-hwaccel", "cuda", "-hwaccel_output_format", "cuda"};
        }
        @Override public String encoder()              { return "h264_nvenc"; }
        @Override public String scaleFilter(String wh) { return "scale_cuda=" + wh + ":format=nv12"; }
        @Override public String preset()               { return "fast"; }
    };

    /**
     * Arguments to add to the UrlInput before {@code -i}.
     * Jaffree places these immediately before the input path, so they are positioned
     * correctly as FFmpeg input/global options.
     */
    public abstract String[] inputArgs(String device);

    /**
     * Hardware encoder name, or {@code null} to use the quality's default software codec.
     * COPY quality always ignores this.
     */
    public abstract String encoder();

    /** Scale filter expression for the given {@code W:H} scale string. */
    public abstract String scaleFilter(String wh);

    /**
     * The {@code -preset} value, or {@code null} if {@code -preset} should be omitted
     * (VAAPI does not support it).
     */
    public abstract String preset();

    public static HardwareAccel fromString(String value) {
        return switch (value.toLowerCase()) {
            case "vaapi" -> VAAPI;
            case "nvdec" -> NVDEC;
            default      -> NONE;
        };
    }
}
