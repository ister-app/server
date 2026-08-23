package app.ister.disk.nfo;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Extracts a book's series position from album.nfo text: the title or the review opening with
 * "{seriesName} {N} - " (dash variants or colon). The number is capped at three digits so a year
 * ("Broederband 2013 - ...") never parses as a position; a decimal comma or dot is accepted
 * ("2,5" / "2.5"). Anchored at the start, so a series name merely mentioned mid-text never
 * matches.
 */
public final class SeriesIndexParser {

    private SeriesIndexParser() {}

    public static Optional<Double> parse(String seriesName, String title, String review) {
        if (seriesName == null || seriesName.isBlank()) {
            return Optional.empty();
        }
        Pattern pattern = Pattern.compile(
                "^\\s*" + Pattern.quote(seriesName.strip())
                        + "\\s+(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*[-–—:]",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return Stream.of(title, review)
                .filter(text -> text != null && !text.isBlank())
                .map(pattern::matcher)
                .filter(Matcher::find)
                .findFirst()
                .map(matcher -> Double.parseDouble(matcher.group(1).replace(',', '.')));
    }
}
