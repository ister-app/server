package app.ister.core.enums;

public enum PlayQueueSourceType {
    MOVIE,
    SHOW,
    ALBUM,
    LIBRARY,
    BOOK,
    PODCAST,
    ARTIST,
    /** A custom filter (saved view or ad-hoc); the definition is pinned on the queue as JSON. */
    FILTER,
}
