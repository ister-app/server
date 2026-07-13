package app.ister.core.config;

import java.util.Collection;
import java.util.Locale;

/**
 * Compares a media stream's language tag against a user's preference list.
 *
 * <p>The two sides speak different dialects of ISO-639: a stream carries whatever ffprobe read from
 * the container ({@code eng}, {@code nld}, sometimes {@code und} or nothing at all), while the player
 * stores ISO-639-1 tags ({@code en}, {@code nl}). Both are normalised to ISO-639-3 before comparing.
 */
public final class LanguageMatcher {

    private LanguageMatcher() {
    }

    /** True when the stream's language is one of the preferred languages. */
    public static boolean matches(String streamLanguage, Collection<String> preferredLanguages) {
        String stream = normalize(streamLanguage);
        if (stream == null) {
            return false;
        }
        return preferredLanguages.stream()
                .map(LanguageMatcher::normalize)
                .anyMatch(stream::equals);
    }

    /**
     * ISO-639-3, lower case. Two-letter tags are widened via {@link Locale}; anything else is passed
     * through lower-cased, so an already three-letter code compares as-is. Unknown or absent
     * languages ({@code und}, empty, null) return null: they never match a preference.
     */
    private static String normalize(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String tag = language.trim().toLowerCase(Locale.ROOT);
        if ("und".equals(tag)) {
            return null;
        }
        if (tag.length() == 2) {
            String iso3 = Locale.forLanguageTag(tag).getISO3Language();
            return iso3.isEmpty() ? tag : iso3;
        }
        return tag;
    }
}
