package app.ister.disk.scanner;

import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.core.storage.PathStrings;
import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import lombok.Getter;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a path relative to a GAME library root. Games are platform-first:
 *
 * <pre>
 *   {libraryRoot}/{platform}/                                   n64, gbc, ps3, pc, linux, steam, ...
 *   {libraryRoot}/{platform}/{Title (Year)}/                    one folder per game; the year is optional
 *   {libraryRoot}/n64/Super Mario 64 (1996)/Super Mario 64.z64  a ROM (entry file) [+ cover.jpg]
 *   {libraryRoot}/psx/Game/Game.cue + Game.bin                  a ROM set: entry + companions
 *   {libraryRoot}/pc/Game (2010)/Game.zip                       portable Windows game
 *   {libraryRoot}/linux/Game/setup_game.sh | game.tar.xz        Linux installer / portable archive
 *   {libraryRoot}/ps3/Game (2009)/PS3_GAME/PARAM.SFO            PS3 folder game, marked by PARAM.SFO
 *   {libraryRoot}/ps3/Game (2009)/BLUS12345/PS3_GAME/PARAM.SFO  the same with a title-id wrapper folder
 *   {libraryRoot}/steam/Game (2015)/appmanifest_12345.acf       Steam game, marked by its manifest ...
 *   {libraryRoot}/steam/Game (2015)/{installdir}/...            ... next to the installed tree
 * </pre>
 *
 * <p>Folder-form games (PS3, Steam) consist of thousands of files; only their <em>marker</em> is a
 * scannable file ({@link FileType#GAME_MARKER}), everything else in the tree is
 * {@link FileType#GAME_TREE_FILE} and is served straight from disk. The scan walker descends into
 * a game's subfolders only for those platforms ({@link #descendIntoTree()}).
 */
@Getter
public class GamePathObject {

    /** A trailing "(YYYY)"; the part before it is the game name. Suffix-anchored so matching stays linear. */
    private static final Pattern YEAR_SUFFIX = Pattern.compile("\\((\\d{4})\\)\\s*+$");
    private static final Pattern STEAM_MANIFEST = Pattern.compile("(?i)^appmanifest_(\\d+)\\.acf$");
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png");
    private static final List<String> GAME_IMAGE_NAMES = List.of("cover", "folder", "poster", "background");
    private static final String PS3_MARKER = "PS3_GAME/PARAM.SFO";

    private GamePlatform platform;
    private String gameName;
    private int pathYear;
    /** The game folder's absolute path (or s3:// uri); set for everything at or below a game folder. */
    private String gameRootPath;
    /** Path of a file/folder relative to the game folder, '/'-separated; null at or above it. */
    private String relativeInGame;
    private DirType dirType = DirType.NONE;
    private FileType fileType = FileType.NONE;
    /** The form a scannable file implies; null for images, tree files and directories. */
    private GameForm form;
    /** For {@link FileType#GAME}: whether this is the launchable file (false for ROM-set companions). */
    private boolean entryFile;
    /** For a Steam marker: the app id from the manifest file name. */
    private Long steamAppId;

    /** Guesses directory-ness from the last segment (no dot = directory); prefer the explicit flag. */
    public GamePathObject(String libraryRootPath, String currentPath) {
        this(libraryRootPath, currentPath, !lastSegmentHasExtension(currentPath));
    }

    /** @param directory whether {@code currentPath} itself is a directory. */
    public GamePathObject(String libraryRootPath, String currentPath, boolean directory) {
        String root = libraryRootPath.endsWith("/") ? libraryRootPath : libraryRootPath + "/";
        if (currentPath == null || !currentPath.startsWith(root)) {
            return;
        }
        String relative = currentPath.substring(root.length());
        if (relative.isEmpty()) {
            return;
        }
        String[] parts = relative.split("/", -1);
        Optional<GamePlatform> parsedPlatform = GamePlatform.fromFolder(parts[0]);
        if (parsedPlatform.isEmpty()) {
            return; // An unknown platform folder (or a loose file in the root) is not part of the grammar.
        }
        platform = parsedPlatform.get();
        if (parts.length == 1) {
            if (directory) {
                dirType = DirType.GAME_PLATFORM;
            }
            return;
        }
        if (parts.length == 2 && !directory) {
            return; // A loose file directly under the platform folder is ignored: games live in a folder.
        }
        gameName = stripYear(parts[1]);
        pathYear = parseYear(parts[1]);
        gameRootPath = root + String.join("/", parts[0], parts[1]);
        if (parts.length == 2) {
            dirType = DirType.GAME;
            return;
        }
        relativeInGame = String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
        if (directory) {
            dirType = DirType.GAME_TREE;
            return;
        }
        dirType = parts.length == 3 ? DirType.GAME : DirType.GAME_TREE;
        classifyFile(parts[parts.length - 1], parts.length == 3);
    }

    private void classifyFile(String fileName, boolean directlyInGameFolder) {
        switch (platform) {
            case PS3 -> classifyPs3File(fileName, directlyInGameFolder);
            case STEAM -> classifySteamFile(fileName, directlyInGameFolder);
            default -> {
                if (directlyInGameFolder) {
                    classifySingleFile(fileName); // nothing below the game folder is part of the grammar
                }
            }
        }
    }

    private void classifyPs3File(String fileName, boolean directlyInGameFolder) {
        if (relativeInGame.endsWith(PS3_MARKER) && isPs3MarkerPlacement()) {
            fileType = FileType.GAME_MARKER;
            form = GameForm.PS3_FOLDER;
        } else {
            classifyTreeFile(fileName, directlyInGameFolder);
        }
    }

    private void classifySteamFile(String fileName, boolean directlyInGameFolder) {
        Matcher manifest = STEAM_MANIFEST.matcher(fileName);
        if (directlyInGameFolder && manifest.matches()) {
            fileType = FileType.GAME_MARKER;
            form = GameForm.STEAM;
            steamAppId = Long.parseLong(manifest.group(1));
        } else {
            classifyTreeFile(fileName, directlyInGameFolder);
        }
    }

    /** Folder forms: an image next to the marker is a cover, everything else belongs to the tree. */
    private void classifyTreeFile(String fileName, boolean directlyInGameFolder) {
        fileType = directlyInGameFolder && isGameImage(fileName.toLowerCase(Locale.ROOT))
                ? FileType.IMAGE : FileType.GAME_TREE_FILE;
    }

    private void classifySingleFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (platform.isEntryFile(fileName)) {
            fileType = FileType.GAME;
            entryFile = true;
            form = formOf(platform, lower);
        } else if (platform.isCompanionFile(fileName)) {
            fileType = FileType.GAME;
            entryFile = false;
            form = GameForm.ROM;
        } else if (isGameImage(lower)) {
            fileType = FileType.IMAGE;
        }
    }

    /** PARAM.SFO directly in the game folder's PS3_GAME, or one wrapper folder deeper (BLUS12345/PS3_GAME). */
    private boolean isPs3MarkerPlacement() {
        int depth = relativeInGame.split("/", -1).length;
        return depth == 2 || depth == 3;
    }

    private static GameForm formOf(GamePlatform platform, String lowerFileName) {
        return switch (platform) {
            case PC -> GameForm.PC_PORTABLE_ZIP;
            case LINUX -> lowerFileName.endsWith(".sh") ? GameForm.PC_LINUX_INSTALLER : GameForm.PC_LINUX_PORTABLE;
            default -> GameForm.ROM;
        };
    }

    private static boolean isGameImage(String lowerFileName) {
        int dot = lowerFileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = lowerFileName.substring(dot + 1);
        String base = lowerFileName.substring(0, dot);
        return IMAGE_EXTENSIONS.contains(ext) && GAME_IMAGE_NAMES.stream().anyMatch(base::contains);
    }

    /** Whether the walker should enter sub-folders of a game of this platform. */
    public boolean descendIntoTree() {
        return platform == GamePlatform.PS3 || platform == GamePlatform.STEAM;
    }

    /** For a PS3 marker: the launch file relative to the game folder (keeps a wrapper folder). */
    public String ps3EntryPath() {
        if (form != GameForm.PS3_FOLDER) {
            return null;
        }
        String prefix = relativeInGame.substring(0, relativeInGame.length() - PS3_MARKER.length());
        return prefix + "PS3_GAME/USRDIR/EBOOT.BIN";
    }

    /** The game folder's own name (last path segment), for display and the upload preview. */
    public String gameFolderName() {
        return gameRootPath == null ? null : PathStrings.fileName(gameRootPath);
    }

    private static String stripYear(String name) {
        return regex(name).map(m -> name.substring(0, m.start()).strip()).orElse(name.strip());
    }

    private static int parseYear(String name) {
        return regex(name).map(m -> Integer.parseInt(m.group(1))).orElse(0);
    }

    private static Optional<MatchResult> regex(String input) {
        return YEAR_SUFFIX.matcher(input).results().findFirst();
    }

    private static boolean lastSegmentHasExtension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.contains(".") && !name.startsWith(".");
    }
}
