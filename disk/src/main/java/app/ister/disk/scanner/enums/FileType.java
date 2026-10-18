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
    COMIC,
    /** A game file in a game folder: the ROM / archive / installer, or a companion of a ROM set. */
    GAME,
    /** The marker of a folder-form game: PS3_GAME/PARAM.SFO or appmanifest_<appid>.acf. */
    GAME_MARKER,
    /** Any other file inside a folder-form game: served from the tree, never scanned on its own. */
    GAME_TREE_FILE
}
