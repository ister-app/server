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
    /**
     * A user's playlist. MANUAL playlists play their items in order (or seeded-shuffled); SMART
     * playlists pin their embedded filter on the queue at creation, like FILTER. A BOOK library's
     * playlist expands each book to its chapters and cannot shuffle.
     */
    PLAYLIST,
}
