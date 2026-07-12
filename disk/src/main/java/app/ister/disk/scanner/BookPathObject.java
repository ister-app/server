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
 * Parses a path relative to a book library root.
 *
 * Expected structure:
 *   {libraryRoot}/{Author Name (optional birth year)}/
 *   {libraryRoot}/{Author Name}/{Book Name}.epub
 *   {libraryRoot}/{Author Name}/{Book Name (optional year)}/NNN_Chapter Title.mp3
 *   {libraryRoot}/{Author Name}/{Book Name}/{Book Name}.epub
 *   {libraryRoot}/{Author Name}/artist.nfo
 *   {libraryRoot}/{Author Name}/{Book Name}/album.nfo
 *   {libraryRoot}/{Author Name}/{Book Name}/cover.jpg
 *
 * <p>A trailing "(karaoke)" on an epub or book directory name is stripped from the book name so
 * the read-aloud edition lands on the same book as the plain epub and the audiobook folder. It is
 * only a naming convention: whether an epub actually has media overlays is detected from its
 * contents, never from the filename.
 *
 * <p>Audiobook chapter numbers come from the leading digits of the filename ("000_Title.mp3",
 * "01 - Title.mp3"). They may be zero-based; only the ordering matters.
 */
@Getter
@Slf4j
public class BookPathObject {

    private static final String REGEX_YEAR = "^(.*?)\\s*\\((\\d{4})\\)\\s*$";
    private static final String REGEX_KARAOKE = "^(.*?)\\s*\\((?i:karaoke)\\)\\s*$";
    private static final String REGEX_CHAPTER_NUMBER = "^(\\d{1,4})(?:[-._\\s].*)?$";
    private static final List<String> AUDIO_EXTENSIONS = List.of("mp3", "flac", "aac", "opus", "ogg", "wav", "m4a", "m4b", "wma");
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png");
    private static final List<String> AUTHOR_IMAGE_NAMES = List.of("artist", "folder", "background", "thumb");
    private static final List<String> BOOK_IMAGE_NAMES = List.of("cover", "folder");
    private static final String EPUB_EXTENSION = "epub";

    private String authorName;
    private int authorYear;
    private String bookName;
    private int bookYear;
    private int chapterNumber;
    private DirType dirType = DirType.NONE;
    private FileType fileType = FileType.NONE;

    public BookPathObject(String libraryRootPath, String currentPath) {
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
            parseOneLevel(parts);
        } else if (parts.length == 2) {
            parseTwoLevels(parts);
        } else if (parts.length >= 3) {
            parseThreePlusLevels(parts);
        }
    }

    private void parseOneLevel(String[] parts) {
        if (!hasExtension(parts[0])) {
            authorName = stripYear(parts[0]);
            authorYear = parseYear(parts[0]);
            dirType = DirType.ARTIST;
        }
    }

    private void parseTwoLevels(String[] parts) {
        authorName = stripYear(parts[0]);
        authorYear = parseYear(parts[0]);
        String item = parts[1];
        if (!hasExtension(item)) {
            // Book directory (audiobook folder, or a folder that also holds an epub).
            parseBookName(item);
            dirType = DirType.ALBUM;
        } else {
            dirType = DirType.ARTIST;
            String ext = getExtension(item).toLowerCase();
            if (EPUB_EXTENSION.equals(ext)) {
                fileType = FileType.EPUB;
                parseBookName(removeExtension(item));
            } else {
                setFileTypeForAuthorFile(item);
            }
        }
    }

    private void parseThreePlusLevels(String[] parts) {
        authorName = stripYear(parts[0]);
        authorYear = parseYear(parts[0]);
        parseBookName(parts[1]);
        dirType = DirType.ALBUM;
        setFileTypeForBookFile(parts[parts.length - 1]);
    }

    private void setFileTypeForAuthorFile(String filename) {
        String ext = getExtension(filename).toLowerCase();
        String nameWithoutExt = removeExtension(filename).toLowerCase();
        if (IMAGE_EXTENSIONS.contains(ext) && AUTHOR_IMAGE_NAMES.stream().anyMatch(nameWithoutExt::contains)) {
            fileType = FileType.IMAGE;
        } else if ("nfo".equals(ext) && "artist".equals(nameWithoutExt)) {
            fileType = FileType.NFO;
        }
    }

    private void setFileTypeForBookFile(String filename) {
        String ext = getExtension(filename).toLowerCase();
        String nameWithoutExt = removeExtension(filename).toLowerCase();
        if (AUDIO_EXTENSIONS.contains(ext)) {
            fileType = FileType.AUDIO;
            parseChapterNumber(filename);
        } else if (EPUB_EXTENSION.equals(ext)) {
            fileType = FileType.EPUB;
        } else if (IMAGE_EXTENSIONS.contains(ext) && BOOK_IMAGE_NAMES.stream().anyMatch(nameWithoutExt::contains)) {
            fileType = FileType.IMAGE;
        } else if ("nfo".equals(ext) && ("album".equals(nameWithoutExt) || "book".equals(nameWithoutExt))) {
            fileType = FileType.NFO;
        }
    }

    private void parseChapterNumber(String filename) {
        Optional<MatchResult> match = regex(REGEX_CHAPTER_NUMBER, removeExtension(filename));
        chapterNumber = match.map(m -> Integer.parseInt(m.group(1))).orElse(0);
    }

    /** Book name = the directory/file name minus a trailing "(karaoke)" and "(YYYY)" (in any order). */
    private void parseBookName(String name) {
        String base = stripKaraoke(name);
        bookYear = parseYear(base);
        base = stripYear(base);
        base = stripKaraoke(base);
        bookName = base.isEmpty() ? name.trim() : base;
    }

    private String stripKaraoke(String name) {
        return regex(REGEX_KARAOKE, name).map(m -> m.group(1).strip()).orElse(name.trim());
    }

    private String stripYear(String name) {
        return regex(REGEX_YEAR, name).map(m -> m.group(1).strip()).orElse(name.trim());
    }

    private int parseYear(String name) {
        return regex(REGEX_YEAR, name).map(m -> Integer.parseInt(m.group(2))).orElse(0);
    }

    private boolean hasExtension(String name) {
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

    private Optional<MatchResult> regex(String regex, String input) {
        Matcher matcher = Pattern.compile(regex).matcher(input);
        return matcher.results().findFirst();
    }
}
