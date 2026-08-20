package app.ister.core.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersonNamesTest {

    @ParameterizedTest
    @CsvSource({
            "ABBA, abba",
            "Abba, abba",
            "'  ABBA   Gold ', abba gold",
            "'Alice  DeeJay', alice deejay",
            "'2 BROTHERS ON THE 4TH FLOOR', 2 brothers on the 4th floor",
    })
    void normalizesCaseAndWhitespace(String raw, String expected) {
        assertEquals(expected, PersonNames.normalize(raw));
    }

    @Test
    void lowercasesLocaleIndependently() {
        // With a Turkish default locale "I" would lowercase to a dotless ı and stop matching.
        assertEquals("izzy", PersonNames.normalize("IZZY"));
    }

    @Test
    void passesNullThrough() {
        assertNull(PersonNames.normalize(null));
    }
}
