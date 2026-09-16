package app.ister.worker.events.tmdbmetadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmdbLanguageFallbackTest {

    @Test
    void untranslatedTitleForAnotherLanguageNeedsFallback() {
        assertTrue(TmdbLanguageFallback.needsFallback("nl", "デスノート", "デスノート", "ja"));
        assertTrue(TmdbLanguageFallback.needsFallback("nl-NL", "デスノート", "デスノート", "ja"));
    }

    @Test
    void translatedTitleKeepsItsLanguage() {
        assertFalse(TmdbLanguageFallback.needsFallback("nl", "Dodenboek", "デスノート", "ja"));
    }

    @Test
    void englishAndTheOriginalLanguageNeverFallBack() {
        assertFalse(TmdbLanguageFallback.needsFallback("en", "Death Note", "デスノート", "ja"));
        assertFalse(TmdbLanguageFallback.needsFallback("nl", "Flikken", "Flikken", "nl"));
        assertFalse(TmdbLanguageFallback.needsFallback("nl", "Show", "Show", "en"));
    }

    @Test
    void missingValuesNeverFallBack() {
        assertFalse(TmdbLanguageFallback.needsFallback(null, "a", "a", "ja"));
        assertFalse(TmdbLanguageFallback.needsFallback("nl", null, "a", "ja"));
        assertFalse(TmdbLanguageFallback.needsFallback("nl", "a", null, "ja"));
        assertFalse(TmdbLanguageFallback.needsFallback("", "a", "a", "ja"));
    }

    @Test
    void pickPrefersTheLocalizedTextUnlessBlank() {
        assertEquals("lokaal", TmdbLanguageFallback.pick("lokaal", "english"));
        assertEquals("english", TmdbLanguageFallback.pick("  ", "english"));
        assertEquals("english", TmdbLanguageFallback.pick(null, "english"));
    }
}
