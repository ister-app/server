package app.ister.core.enums;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The systems a GAME library knows, keyed by the platform folder directly under the library root
 * ({@code <dir>/<platform>/<Title (Year)>/...}). Each platform carries what the scanner and the
 * client need and nothing an admin would edit, so it is code rather than a table: the folder
 * names it answers to, the file extensions that make a file the launchable entry of a ROM game,
 * the companion extensions that travel with it (the .bin tracks of a .cue), and the launcher the
 * client uses by default (RetroArch with a libretro core, RPCS3, the native exe, Steam).
 */
public enum GamePlatform {
    NES(List.of("nes"), List.of("nes", "fds"), List.of(), GameLauncherKind.RETROARCH, "mesen"),
    SNES(List.of("snes", "sfc"), List.of("sfc", "smc"), List.of(), GameLauncherKind.RETROARCH, "snes9x"),
    N64(List.of("n64"), List.of("z64", "n64", "v64"), List.of(), GameLauncherKind.RETROARCH, "mupen64plus_next"),
    GB(List.of("gb"), List.of("gb"), List.of(), GameLauncherKind.RETROARCH, "gambatte"),
    GBC(List.of("gbc"), List.of("gbc"), List.of(), GameLauncherKind.RETROARCH, "gambatte"),
    GBA(List.of("gba"), List.of("gba"), List.of(), GameLauncherKind.RETROARCH, "mgba"),
    NDS(List.of("nds"), List.of("nds"), List.of(), GameLauncherKind.RETROARCH, "melonds"),
    PSX(List.of("psx", "ps1"), List.of("cue", "chd", "pbp"), List.of("bin", "sub", "img", "ccd"),
            GameLauncherKind.RETROARCH, "swanstation"),
    PS2(List.of("ps2"), List.of("iso", "chd"), List.of(), GameLauncherKind.RETROARCH, "pcsx2"),
    GC(List.of("gc", "gamecube"), List.of("iso", "gcm", "rvz"), List.of(), GameLauncherKind.RETROARCH, "dolphin"),
    WII(List.of("wii"), List.of("iso", "rvz", "wbfs"), List.of(), GameLauncherKind.RETROARCH, "dolphin"),
    /** Folder games: a {@code PS3_GAME/PARAM.SFO} marks the game, no ROM extensions. */
    PS3(List.of("ps3"), List.of(), List.of(), GameLauncherKind.RPCS3, null),
    /** Windows games as a portable zip. */
    PC(List.of("pc", "windows"), List.of("zip"), List.of(), GameLauncherKind.NATIVE_WINDOWS, null),
    /** Linux-native games: a GOG-style .sh installer or a portable tar archive. */
    LINUX(List.of("linux"), List.of("sh", "tar", "tgz", "tar.gz", "tar.xz"), List.of(), GameLauncherKind.NATIVE_LINUX, null),
    /** Folder games marked by {@code appmanifest_<appid>.acf}. */
    STEAM(List.of("steam"), List.of(), List.of(), GameLauncherKind.STEAM, null);

    private final List<String> folderNames;
    private final List<String> entryExtensions;
    private final List<String> companionExtensions;
    private final GameLauncherKind launcher;
    private final String retroArchCore;

    GamePlatform(List<String> folderNames, List<String> entryExtensions, List<String> companionExtensions,
                 GameLauncherKind launcher, String retroArchCore) {
        this.folderNames = folderNames;
        this.entryExtensions = entryExtensions;
        this.companionExtensions = companionExtensions;
        this.launcher = launcher;
        this.retroArchCore = retroArchCore;
    }

    /** The folder names (lowercase) this platform answers to under the library root. */
    public List<String> folderNames() {
        return folderNames;
    }

    /** Extensions (lowercase, without dot; may be compound like {@code tar.gz}) of a launchable file. */
    public List<String> entryExtensions() {
        return entryExtensions;
    }

    /** Extensions of files that belong to a ROM set but are never launched themselves. */
    public List<String> companionExtensions() {
        return companionExtensions;
    }

    public GameLauncherKind launcher() {
        return launcher;
    }

    /** The libretro core name ({@code <core>_libretro.so}); null for platforms not run by RetroArch. */
    public String retroArchCore() {
        return retroArchCore;
    }

    /** The platform of a folder name under the library root, case-insensitive; empty for unknown names. */
    public static Optional<GamePlatform> fromFolder(String folderName) {
        if (folderName == null) {
            return Optional.empty();
        }
        String lower = folderName.strip().toLowerCase(Locale.ROOT);
        for (GamePlatform platform : values()) {
            if (platform.folderNames.contains(lower)) {
                return Optional.of(platform);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code fileName} (any case) ends in one of this platform's entry extensions. */
    public boolean isEntryFile(String fileName) {
        return matches(fileName, entryExtensions);
    }

    public boolean isCompanionFile(String fileName) {
        return matches(fileName, companionExtensions);
    }

    private static boolean matches(String fileName, List<String> extensions) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(ext -> lower.endsWith("." + ext));
    }
}
