package app.ister.disk.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits an {@code artist} tag into the primary artist and its featured guests, so a track credited
 * as "Blu Cantrell ft. Sean Paul" lands on both artists' pages instead of creating a third one.
 *
 * <p>Only "feat."/"ft."/"featuring" is split on. An ampersand is never a split: "Simon &amp;
 * Garfunkel", "Mumford &amp; Sons" and "Nick &amp; Simon" are single acts and nothing in the tag
 * tells them apart from a collaboration — inventing an artist is worse than missing a credit.
 * Within the featured part the context is unambiguous, so "feat. A &amp; B" does yield two guests.
 */
public final class ArtistTagParser {
    // The patterns hold no repetition at all: the whitespace and bracket runs around a separator
    // are trimmed in Java instead, so a long tag can never be rescanned at every offset in a run.
    private static final Pattern FEATURING =
            Pattern.compile("\\b(?:featuring|feat|ft)\\b\\.?", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUEST_SEPARATOR =
            Pattern.compile("[,&/+]|\\band\\b", Pattern.CASE_INSENSITIVE);

    private ArtistTagParser() {
    }

    /** The primary artist and its featured guests; the guest list is empty for a plain tag. */
    public record Credits(String primary, List<String> featured) {
    }

    public static Credits parse(String tag) {
        if (tag == null || tag.isBlank()) return new Credits(null, List.of());
        String cleaned = tag.strip();
        Matcher matcher = FEATURING.matcher(cleaned);
        if (!matcher.find()) return new Credits(cleaned, List.of());
        String primary = stripSeparators(cleaned.substring(0, matcher.start()));
        if (primary.isEmpty()) {
            // A tag that is nothing but "feat. X" carries no primary artist at all.
            return new Credits(cleaned, List.of());
        }
        String guestPart = stripSeparators(cleaned.substring(matcher.end()));
        List<String> featured = new ArrayList<>();
        for (String guest : GUEST_SEPARATOR.split(guestPart)) {
            String name = guest.strip();
            if (!name.isEmpty() && !name.equalsIgnoreCase(primary)) featured.add(name);
        }
        return new Credits(primary, List.copyOf(featured));
    }

    /** Strips the whitespace and brackets that surround a separator: "Robin Schulz (" → "Robin Schulz". */
    private static String stripSeparators(String part) {
        int start = 0;
        int end = part.length();
        while (start < end && isSeparator(part.charAt(start))) start++;
        while (end > start && isSeparator(part.charAt(end - 1))) end--;
        return part.substring(start, end);
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == '(' || c == '[' || c == ')' || c == ']';
    }

    /** The primary artist of a tag, or null when the tag is empty. */
    public static String primary(String tag) {
        return parse(tag).primary();
    }
}
