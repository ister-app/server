package app.ister.server.scanner;

import app.ister.server.scanner.enums.DirType;
import app.ister.server.scanner.enums.FileType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Slf4j
public class PathObject {

    private String show;
    private int showYear;
    private int season;
    private int episode;
    private DirType dirType;
    private FileType fileType;

    private final static String REGEX_SHOW = ".*\\/(.*)\\((\\d{4})\\)*";
    private final static String REGEX_SEASON = "season\\s+(\\d{1,4})";
    private final static String REGEX_EPISODE = "s(\\d{1,4})e(\\d{1,4}).*";
    private final static String REGEX_FILE_TYPE = ".*\\.(.*)";

    private final static List<String> IMAGE_FILE_TYPES = List.of("jpg", "png");
    private final static List<String> NFO_FILE_TYPES = List.of("nfo");
    private final static List<String> MEDIA_FILES_FILE_TYPES = List.of("mkv", "mp4");

    public PathObject(String path) {
        if (setShow(path)) {
            setSeason(path);
            setEpisode(path);
            setFileType(path);
        }
        if (dirType == null) {
            dirType = DirType.NONE;
            fileType = FileType.NONE;
        }
    }

    private Boolean setShow(String path) {
        var matches = regex(REGEX_SHOW, path);
        if (matches.isPresent()) {
            show = matches.get().group(1).trim();
            showYear = Integer.parseInt(matches.get().group(2));
            dirType = DirType.SHOW;
            return true;
        } else {
            return false;
        }
    }

    private Boolean setSeason(String path) {
        var matches = regex(REGEX_SEASON, path.toLowerCase());
        if (matches.isPresent()) {
            season = Integer.parseInt(matches.get().group(1));
            dirType = DirType.SEASON;
            return true;
        } else {
            return false;
        }
    }

    private Boolean setEpisode(String path) {
        var matches = regex(REGEX_EPISODE, path.toLowerCase());
        if (matches.isPresent()) {
            season = Integer.parseInt(matches.get().group(1));
            episode = Integer.parseInt(matches.get().group(2));
            dirType = DirType.EPISODE;
            return true;
        } else {
            return false;
        }
    }

    private void setFileType(String path) {
        var matches = regex(REGEX_FILE_TYPE, path);
        if (matches.isPresent()) {
            String fileTypeString = matches.get().group(1).toLowerCase();
            if (IMAGE_FILE_TYPES.contains(fileTypeString)) {
                fileType = FileType.IMAGE;
            } else if (NFO_FILE_TYPES.contains(fileTypeString)) {
                fileType = FileType.NFO;
            } else if (MEDIA_FILES_FILE_TYPES.contains(fileTypeString)) {
                fileType = FileType.MEDIA;
            } else {
                fileType = FileType.NONE;
            }
        } else {
            fileType = FileType.NONE;
        }
    }

    private Optional<MatchResult> regex(String regex, String string) {
        final Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        final Matcher matcher = pattern.matcher(string);
        return matcher.results().findFirst();
    }
}

