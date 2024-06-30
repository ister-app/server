package app.ister.server.eventHandlers.subtitleFileFound;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SubtitleFilePathParser {
    private final static String REGEX_FOR_LANG_CODE = ".*\\.(.*)\\.srt";

    /**
     * Get the 3-letter iso 3 code from a path string.
     * So the path "/mnt/shows/Show (2018)/Season 01/s01e01.nl.srt" will return "NLD".
     */
    public static String langCodeToIso3(String path) {
        var matches = regex(REGEX_FOR_LANG_CODE, path);
        return matches.map(
                        matchResult -> Locale.forLanguageTag(matchResult.group(1).trim()).getISO3Language())
                .orElse(null);
    }

    private static Optional<MatchResult> regex(String regex, String string) {
        final Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        final Matcher matcher = pattern.matcher(string);
        return matcher.results().findFirst();
    }

    /**
     * If the subtitle path is the same until the subtitle lang en file extension name.
     * So this two files belong together:
     * - /mnt/shows/Show (2018)/Season 01/s01e01.en.srt
     * - /mnt/shows/Show (2018)/Season 01/s01e01.mkv
     */
    public static Boolean mediaFileAndSubtitleFileBelongTogether(String mediaFilePath, String subtitleFilePath) {
        return subtitleFilePath.contains(removeExtension(mediaFilePath));
    }

    private static String removeExtension(String string) {
        return string.replaceFirst("[.][^.]+$", "");
    }
}
