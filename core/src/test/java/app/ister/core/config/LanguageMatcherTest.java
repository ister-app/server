package app.ister.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageMatcherTest {

    @Test
    void twoLetterPreferenceMatchesThreeLetterStreamTag() {
        // The player stores "nl"; ffprobe wrote "nld" into the container.
        assertTrue(LanguageMatcher.matches("nld", List.of("nl", "en")));
        assertTrue(LanguageMatcher.matches("eng", List.of("nl", "en")));
    }

    @Test
    void threeLetterPreferenceMatchesAsIs() {
        assertTrue(LanguageMatcher.matches("nld", Set.of("nld")));
    }

    @Test
    void unrelatedLanguageDoesNotMatch() {
        assertFalse(LanguageMatcher.matches("fra", List.of("nl", "en")));
    }

    @Test
    void unknownOrAbsentStreamLanguageNeverMatches() {
        assertFalse(LanguageMatcher.matches("und", List.of("nl", "en")));
        assertFalse(LanguageMatcher.matches(null, List.of("nl", "en")));
        assertFalse(LanguageMatcher.matches("", List.of("nl", "en")));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(LanguageMatcher.matches("NLD", List.of("NL")));
    }
}
