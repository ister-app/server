package app.ister.core.enums;

/**
 * Ranking of the library Discover top-lists and of ARTIST play queues; RECENTLY_PLAYED doubles as
 * recently read. RECENTLY_ADDED (newest in the library first) is an artist ranking only — the
 * Discover lists have their own recently-added row and return an empty page for it.
 */
public enum RankKind {
    RECENTLY_PLAYED,
    MOST_PLAYED,
    HIGHEST_RATED,
    RECENTLY_ADDED
}
