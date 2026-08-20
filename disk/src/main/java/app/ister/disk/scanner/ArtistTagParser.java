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
    // Leading whitespace and an opening bracket are one character class, and the separator
    // quantifiers are possessive: both keep matching linear on a long tag instead of backtracking.
    private static final Pattern FEATURING =
            Pattern.compile("[\\s(\\[]*+\\b(?:featuring|feat|ft)\\b\\.?+\\s++", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUEST_SEPARATOR =
            Pattern.compile("\\s*+[,&/+]\\s*+|\\s++and\\s++", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_BRACKET = Pattern.compile("[)\\]]\\s*$");

    private ArtistTagParser() {
    }

    /** The primary artist and its featured guests; the guest list is empty for a plain tag. */
    public record Credits(String primary, List<String> featured) {
    }

    public static Credits parse(String tag) {
        if (tag == null || tag.isBlank()) return new Credits(null, List.of());
        String cleaned = tag.strip();
        Matcher matcher = FEATURING.matcher(cleaned);
        if (!matcher.find() || matcher.start() == 0) {
            // No guests, or a tag that starts with "feat." and so carries no primary artist at all.
            return new Credits(cleaned, List.of());
        }
        String primary = cleaned.substring(0, matcher.start()).strip();
        String guestPart = TRAILING_BRACKET.matcher(cleaned.substring(matcher.end()).strip()).replaceAll("").strip();
        List<String> featured = new ArrayList<>();
        for (String guest : GUEST_SEPARATOR.split(guestPart)) {
            String name = guest.strip();
            if (!name.isEmpty() && !name.equalsIgnoreCase(primary)) featured.add(name);
        }
        return new Credits(primary.isEmpty() ? cleaned : primary, List.copyOf(featured));
    }

    /** The primary artist of a tag, or null when the tag is empty. */
    public static String primary(String tag) {
        return parse(tag).primary();
    }
}
