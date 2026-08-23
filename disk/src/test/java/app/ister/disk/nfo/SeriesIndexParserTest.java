package app.ister.disk.nfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriesIndexParserTest {

    @Test
    void parsesTheIndexFromTheReviewOpening() {
        Optional<Double> index = SeriesIndexParser.parse("Broederband",
                "Broederband - De indringers",
                "Broederband 2 - Hal en de andere jonge krijgers gaan op zoek. Vanaf ca. 10 jaar.");

        assertEquals(Optional.of(2.0), index);
    }

    @Test
    void theTitleWinsOverTheReview() {
        Optional<Double> index = SeriesIndexParser.parse("Broederband",
                "Broederband 3 - De jagers",
                "Broederband 2 - iets anders.");

        assertEquals(Optional.of(3.0), index);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "'Broederband 2,5 – Tussendeel', 2.5", // decimal comma, en dash
            "'Broederband 2.5: Tussendeel', 2.5", // decimal dot, colon separator
            "'broederband 7 — De Caldera', 7.0", // case-insensitive, em dash
            "'  Broederband 9 - De jacht', 9.0", // leading whitespace
    })
    void parsesIndexVariants(String review, double expected) {
        assertEquals(Optional.of(expected), SeriesIndexParser.parse("Broederband", null, review));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource({
            "'Hal en de Broederband 2 - gaan op zoek'", // series mentioned mid-text, anchored so no match
            "'Broederband 2 zonder separator'", // no separator after the number
            "'Broederband 2013 - een jaartal is geen volgnummer'", // four digits never match
            "'Broederband - De indringers'", // no number at all
    })
    void doesNotMatch(String review) {
        assertTrue(SeriesIndexParser.parse("Broederband", null, review).isEmpty());
    }

    @Test
    void handlesDiacriticsInTheSeriesName() {
        Optional<Double> index = SeriesIndexParser.parse("Onder één vlag", null,
                "ONDER ÉÉN VLAG 4 - Het vervolg.");

        assertEquals(Optional.of(4.0), index);
    }

    @Test
    void aSeriesNameWithRegexMetacharactersIsQuoted() {
        Optional<Double> index = SeriesIndexParser.parse("Wat (nu)?", null,
                "Wat (nu)? 2 - Het vervolg.");

        assertEquals(Optional.of(2.0), index);
    }

    @Test
    void nullAndBlankInputsYieldEmpty() {
        assertTrue(SeriesIndexParser.parse(null, "titel", "review").isEmpty());
        assertTrue(SeriesIndexParser.parse("  ", "titel", "review").isEmpty());
        assertTrue(SeriesIndexParser.parse("Broederband", null, null).isEmpty());
        assertTrue(SeriesIndexParser.parse("Broederband", " ", "").isEmpty());
    }
}
