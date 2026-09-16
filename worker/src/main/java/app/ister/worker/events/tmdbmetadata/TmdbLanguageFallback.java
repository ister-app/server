package app.ister.worker.events.tmdbmetadata;

import java.util.Locale;

/**
 * TMDB has no "untranslated" signal: asked for a language it has no translation in, the details
 * response simply echoes the original title (a Japanese anime title for a Dutch request) with an
 * empty overview. This detects that case so the per-language handlers can fetch the English
 * details instead and use those texts for the requested language's metadata row.
 */
final class TmdbLanguageFallback {
    static final String FALLBACK_LANGUAGE = "en";

    private TmdbLanguageFallback() {
    }

    /**
     * True when the localized title is just the original title echoed back for a language that
     * is neither the original nor the fallback language: there is no translation to keep.
     */
    static boolean needsFallback(String requestedLanguage, String title, String originalTitle, String originalLanguage) {
        if (requestedLanguage == null || title == null || originalTitle == null) {
            return false;
        }
        String requested = Locale.forLanguageTag(requestedLanguage).getLanguage();
        if (requested.isEmpty() || requested.equalsIgnoreCase(FALLBACK_LANGUAGE)) {
            return false;
        }
        if (originalLanguage != null
                && (originalLanguage.equalsIgnoreCase(requested) || originalLanguage.equalsIgnoreCase(FALLBACK_LANGUAGE))) {
            // The echoed original already is the requested-language or the English title.
            return false;
        }
        return title.equals(originalTitle);
    }

    /** The localized text, or the fallback when the localized one is missing or blank. */
    static String pick(String localized, String fallback) {
        return localized == null || localized.isBlank() ? fallback : localized;
    }
}
