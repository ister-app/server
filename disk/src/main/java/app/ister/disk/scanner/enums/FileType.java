package app.ister.disk.scanner.enums;

public enum FileType {
    NONE,
    MEDIA,
    IMAGE,
    NFO,
    SUBTITLE,
    AUDIO,
    EPUB,
    /** A comic volume file (cbz, pdf or epub); the ComicScanner picks the follow-up event. */
    COMIC
}
