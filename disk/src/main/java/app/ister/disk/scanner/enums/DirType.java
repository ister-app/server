package app.ister.disk.scanner.enums;

public enum DirType {
    NONE,
    MOVIE,
    SHOW,
    SEASON,
    EPISODE,
    ARTIST,
    ALBUM,
    /** A comic series directory (COMIC libraries are series-first). */
    SERIES,
    /** A platform folder directly under a GAME library root (n64, ps3, steam, ...). */
    GAME_PLATFORM,
    /** A game folder ("Title (Year)") under a platform folder. */
    GAME,
    /** A folder inside a game folder; only walked for folder-form platforms (PS3, Steam). */
    GAME_TREE
}
