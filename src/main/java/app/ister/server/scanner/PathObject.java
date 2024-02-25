package app.ister.server.scanner;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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
    private PathType type;

    private final static String REGEX_SHOW = ".*\\/(.*)\\((\\d{4})\\)*";
    private final static String REGEX_SEASON = "season\\s+(\\d{1,4})";
    private final static String REGEX_EPISODE = "s(\\d{1,4})e(\\d{1,4}).*";

    public PathObject(String path) {
        setShow(path);
        setSeason(path);
        setEpisode(path);
    }

    private void setShow(String path) {
        var matches = regex(REGEX_SHOW, path);
        if (matches.isPresent()) {
            show = matches.get().group(1).trim();
            showYear = Integer.parseInt(matches.get().group(2));
            type = PathType.SHOW;
        }
    }

    private void setSeason(String path) {
        var matches = regex(REGEX_SEASON, path.toLowerCase());
        if (matches.isPresent()) {
            season = Integer.parseInt(matches.get().group(1));
            type = PathType.SEASON;
        }
    }

    private void setEpisode(String path) {
        var matches = regex(REGEX_EPISODE, path);
        if (matches.isPresent()) {
            season = Integer.parseInt(matches.get().group(1));
            episode = Integer.parseInt(matches.get().group(2));
            type = PathType.EPISODE;
        }
    }

    private Optional<MatchResult> regex(String regex, String string) {
        final Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        final Matcher matcher = pattern.matcher(string);
        return matcher.results().findFirst();
    }
}

