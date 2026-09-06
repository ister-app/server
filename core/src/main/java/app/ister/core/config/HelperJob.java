package app.ister.core.config;

/**
 * The CPU-heavy, directory-scoped job families a node can run for directories it does not
 * own (see {@link HelperProperties}). Every value maps to one or more directory-scoped queue
 * bases in {@link app.ister.core.MessageQueue}; anything not listed here always stays on the
 * node that owns the directory.
 */
public enum HelperJob {
    /** HLS transcoding: {@code TranscodeRequested} and {@code TranscodePassRequested}. */
    TRANSCODE,
    /** Intro/outro fingerprinting: {@code DetectSegments}. */
    DETECT_SEGMENTS,
    /** Embedded subtitle extraction and OCR: {@code SubtitleExtractRequested}. */
    SUBTITLES
}
