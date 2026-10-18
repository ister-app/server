package app.ister.core.enums;

public enum LibraryType {
    MOVIE,
    SHOW,
    MUSIC,
    BOOK,
    PODCAST,
    /** Comics (pdf/cbz/epub); series-first layout: one directory per series. */
    COMIC,
    /** Emulator ROMs, PS3 folders, PC and Steam games; played on the client, saves synced here. */
    GAME,
}
