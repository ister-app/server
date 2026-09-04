package app.ister.disk.scanner;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a comic volume filename into an identity name, series position and display title.
 *
 * <p>The documented convention is {@code Volume 27.cbz} / {@code Vol 3 - Subtitle.pdf} /
 * {@code Issue 8.cbz} inside a series directory, but the parser is deliberately tolerant of
 * common wild patterns: {@code attackontitan_vol27.pdf}, {@code rickandmorty2023_issue8.pdf},
 * {@code something#12.cbz}, underscores or spaces, any casing.
 *
 * <p>The identity name is the basename minus a trailing {@code -N} dedupe suffix, so
 * {@code ...part2.pdf} and {@code ...part2-1.pdf} (a re-download of the same volume) and the
 * pdf/cbz/epub versions of one volume all converge on a single volume row.
 */
public final class ComicFileNameParser {

    /**
     * "vol 27", "vol.27", "volume27", "attackontitan_vol27" — fractions allowed (calibre-style
     * 1.5). A plain \b doesn't cut it: '_' is a word character, so "_vol27" has no boundary; what
     * must NOT match is a letter directly before it ("evolution").
     */
    private static final Pattern VOLUME = Pattern.compile("(?i)(?<![a-z])vol(?:ume)?[ ._-]*(\\d+(?:\\.\\d+)?)");
    private static final Pattern ISSUE = Pattern.compile("(?i)(?<![a-z])issue[ ._-]*+(\\d++)|#(\\d++)");
    /** A trailing "-N": a filesystem dedupe suffix on a re-downloaded file ("...part2-1"). */
    private static final Pattern DEDUPE_SUFFIX = Pattern.compile("-\\d+$");

    /**
     * @param identityName the basename without extension and dedupe suffix; becomes BookEntity.name
     * @param number       the volume/issue number, or null when none could be parsed (orders last)
     * @param displayTitle the clean display title ("Volume 27", "Issue 8", or the subtitle)
     */
    public record ComicName(String identityName, Double number, String displayTitle) {}

    private ComicFileNameParser() {
    }

    /** @param basename the filename without its extension */
    public static ComicName parse(String basename) {
        String identity = stripDedupeSuffix(basename.strip());

        Matcher volume = VOLUME.matcher(identity);
        if (volume.find()) {
            Double number = Double.valueOf(volume.group(1));
            String subtitle = subtitleAfter(identity, volume.end());
            return new ComicName(identity, number, subtitle != null ? subtitle : "Volume " + formatNumber(number));
        }
        Matcher issue = ISSUE.matcher(identity);
        if (issue.find()) {
            String digits = issue.group(issue.group(1) != null ? 1 : 2);
            Double number = Double.valueOf(digits);
            String subtitle = subtitleAfter(identity, issue.end());
            return new ComicName(identity, number, subtitle != null ? subtitle : "Issue " + formatNumber(number));
        }
        int digitsStart = trailingDigitsStart(identity);
        if (digitsStart > 0 && digitsStart < identity.length()) {
            String digits = identity.substring(digitsStart);
            return new ComicName(identity, Double.valueOf(digits),
                    humanize(identity.substring(0, digitsStart)) + " " + digits);
        }
        return new ComicName(identity, null, humanize(identity));
    }

    /**
     * Start of the trailing digit run ("fairytail 3", "chapter12"), or {@code length()} when the
     * name does not end in a digit.
     */
    private static int trailingDigitsStart(String value) {
        int start = value.length();
        while (start > 0 && isAsciiDigit(value.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Strips a trailing "-N" only when what remains still carries a digit — then the "-N" was a
     * dedupe suffix on a re-download ("...part2-1"), not the volume identity itself. "issue8" has
     * no dash and "x-23" (no other digit) both stay untouched.
     */
    private static String stripDedupeSuffix(String basename) {
        String candidate = DEDUPE_SUFFIX.matcher(basename).replaceFirst("");
        return !candidate.equals(basename) && candidate.chars().anyMatch(Character::isDigit)
                ? candidate
                : basename;
    }

    /** The tokens after the number ("..._vol1_thespaceshakesagapart1" → "thespaceshakesagapart1"). */
    private static String subtitleAfter(String name, int end) {
        String rest = name.substring(end).replaceAll("^[ ._-]+", "").strip();
        return rest.isEmpty() ? null : humanize(rest);
    }

    /** Underscores to spaces, collapse whitespace; keeps the original casing otherwise. */
    private static String humanize(String value) {
        return value.replace('_', ' ').replaceAll("\\s+", " ").strip();
    }

    /** "27.0" reads as "27"; a genuine fraction ("1.5") stays. */
    private static String formatNumber(Double number) {
        return number == Math.floor(number)
                ? String.valueOf(number.longValue())
                : String.format(Locale.ROOT, "%s", number);
    }
}
