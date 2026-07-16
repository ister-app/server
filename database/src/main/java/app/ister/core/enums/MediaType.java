package app.ister.core.enums;

public enum MediaType {
    MOVIE,
    EPISODE,
    TRACK,
    /** An audiobook chapter; streams like a track. */
    CHAPTER,
    /** An epub being read; never streamed, only used for watch status and recently-watched. */
    BOOK,
    /** A podcast episode; streams like a track once downloaded to the cache directory. */
    PODCAST_EPISODE,
    /**
     * A comic volume being read; never streamed. Used for watch status and continue-watching,
     * where entries are grouped per comic series.
     */
    COMIC
}
