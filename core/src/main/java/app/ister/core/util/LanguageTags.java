package app.ister.core.util;

import java.util.Locale;

/** Language-tag conversions shared by the file-metadata writers (epub OPF, nfo). */
public final class LanguageTags {

    private LanguageTags() {}

    /**
     * ISO-639-3 code ("nld") for a BCP-47 tag ("nl"), the convention stored in
     * {@code MetadataEntity.language}; null for a null, blank or unresolvable tag.
     */
    public static String toIso3(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return null;
        }
        try {
            String iso3 = Locale.forLanguageTag(languageTag.strip()).getISO3Language();
            return iso3.isBlank() ? null : iso3;
        } catch (RuntimeException _) {
            return null;
        }
    }
}
