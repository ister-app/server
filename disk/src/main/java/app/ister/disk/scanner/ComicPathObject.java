package app.ister.disk.scanner;

import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a path relative to a COMIC library root. Comics are series-first — the opposite of the
 * author-first book grammar:
 *
 *   {libraryRoot}/{Series Name (optional start year)}/
 *   {libraryRoot}/{Series Name}/Volume 27.cbz            (documented convention)
 *   {libraryRoot}/{Series Name}/Vol 3 - Subtitle.pdf
 *   {libraryRoot}/{Series Name}/Issue 8.epub
 *   {libraryRoot}/{Series Name}/attackontitan_vol27.pdf  (tolerated wild patterns)
 *   {libraryRoot}/{Series Name}/cover.jpg                (series artwork)
 *
 * <p>The "(YYYY)" suffix on the series directory is the series start year — not an author birth
 * year; comics have no author in the path. Volume number/title parsing is delegated to
 * {@link ComicFileNameParser}. Anything nested deeper than the series directory is ignored.
 */
@Getter
@Slf4j
public class ComicPathObject {

    private static final String REGEX_YEAR = "^(.*?)\\s*\\((\\d{4})\\)\\s*$";
    private static final List<String> COMIC_EXTENSIONS = List.of("cbz", "pdf", "epub");
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png");
    private static final List<String> SERIES_IMAGE_NAMES = List.of("cover", "folder", "poster", "background");

    private String seriesName;
    private int startYear;
    private String volumeName;
    private Double volumeNumber;
    private String volumeTitle;
    private String extension;
    private DirType dirType = DirType.NONE;
    private FileType fileType = FileType.NONE;

    /**
     * Guesses whether the path is a directory from its last segment: no dot means directory. Only
     * for callers that don't know; a series like "Dr. Stone" defeats the guess, so pass the
     * explicit flag whenever the caller knows what the path points at.
     */
    public ComicPathObject(String libraryRootPath, String currentPath) {
        this(libraryRootPath, currentPath, !lastSegmentHasExtension(currentPath));
    }

    /** @param directory whether {@code currentPath} itself is a directory (files' parent segments are always directories). */
    public ComicPathObject(String libraryRootPath, String currentPath, boolean directory) {
        String root = libraryRootPath.endsWith("/") ? libraryRootPath : libraryRootPath + "/";
        if (!currentPath.startsWith(root)) {
            return;
        }
        String relative = currentPath.substring(root.length());
        if (relative.isEmpty()) {
            return;
        }
        String[] parts = relative.split("/", -1);
        if (parts.length == 1) {
            parseSeriesDir(parts[0], directory);
        } else if (parts.length == 2) {
            parseSeriesFile(parts[0], parts[1], directory);
        }
        // Deeper nesting is not part of the grammar; the visitor never descends into it.
    }

    private void parseSeriesDir(String name, boolean directory) {
        if (!directory) {
            return; // A loose file directly under the library root is ignored.
        }
        seriesName = stripYear(name);
        startYear = parseYear(name);
        dirType = DirType.SERIES;
    }

    private void parseSeriesFile(String seriesDir, String filename, boolean directory) {
        seriesName = stripYear(seriesDir);
        startYear = parseYear(seriesDir);
        dirType = DirType.SERIES;
        if (directory) {
            return; // A directory inside a series dir carries no file information.
        }

        String ext = getExtension(filename).toLowerCase();
        String base = removeExtension(filename);
        if (COMIC_EXTENSIONS.contains(ext)) {
            fileType = FileType.COMIC;
            extension = ext;
            ComicFileNameParser.ComicName parsed = ComicFileNameParser.parse(base);
            volumeName = parsed.identityName();
            volumeNumber = parsed.number();
            volumeTitle = parsed.displayTitle();
        } else if (IMAGE_EXTENSIONS.contains(ext)
                && SERIES_IMAGE_NAMES.stream().anyMatch(base.toLowerCase()::contains)) {
            fileType = FileType.IMAGE;
        }
    }

    private String stripYear(String name) {
        return regex(name).map(m -> m.group(1).strip()).orElse(name.trim());
    }

    private int parseYear(String name) {
        return regex(name).map(m -> Integer.parseInt(m.group(2))).orElse(0);
    }

    private Optional<MatchResult> regex(String input) {
        Matcher matcher = Pattern.compile(REGEX_YEAR).matcher(input);
        return matcher.results().findFirst();
    }

    private static boolean lastSegmentHasExtension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.contains(".") && !name.startsWith(".");
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }

    private String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }
}
