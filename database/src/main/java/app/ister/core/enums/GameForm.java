package app.ister.core.enums;

/**
 * What a game's files look like on disk, which decides how the client installs and launches it.
 * Single-file forms attach their file(s) as media files; folder forms are one game row with a
 * {@code rootPath} and are served file-by-file from a manifest.
 */
public enum GameForm {
    /** One ROM file (plus companions like the .bin tracks of a .cue); launched in a RetroArch core. */
    ROM,
    /** A PS3 game folder ({@code PS3_GAME/USRDIR/EBOOT.BIN}); launched with RPCS3. */
    PS3_FOLDER,
    /** A zip of a ready-to-run Windows game folder; the client extracts it and starts the exe. */
    PC_PORTABLE_ZIP,
    /** A GOG-style Linux installer ({@code .sh}); the client runs it once into its install dir. */
    PC_LINUX_INSTALLER,
    /** A tar(.gz/.xz) of a ready-to-run Linux game folder. */
    PC_LINUX_PORTABLE,
    /**
     * An installed Steam game: the {@code steamapps/common/<installdir>} tree plus its
     * {@code appmanifest_<appid>.acf}. The client puts both back into a local Steam library and
     * starts it through Steam ({@code steam://rungameid/<appid>}); the user must own the game.
     */
    STEAM;

    /** Folder forms have no per-file rows; their files are listed live from {@code rootPath}. */
    public boolean isFolder() {
        return this == PS3_FOLDER || this == STEAM;
    }
}
