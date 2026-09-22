package app.ister.disk.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtistTagParserTest {

    @ParameterizedTest
    @CsvSource({
            "Mark Mancina feat. Phil Collins, Mark Mancina, Phil Collins",
            "'BLU CANTRELL  FT. SEAN PAUL', BLU CANTRELL, SEAN PAUL",
            "Eminem ft Dido, Eminem, Dido",
            "Jay-Z Featuring Alicia Keys, Jay-Z, Alicia Keys",
            "Robin Schulz (feat. Jasmine Thompson), Robin Schulz, Jasmine Thompson",
            "Calvin Harris [ft. Rihanna], Calvin Harris, Rihanna",
    })
    void splitsTheFeaturedGuestOffThePrimaryArtist(String tag, String primary, String guest) {
        ArtistTagParser.Credits credits = ArtistTagParser.parse(tag);

        assertEquals(primary, credits.primary());
        assertEquals(List.of(guest), credits.featured());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Band names, not collaborations: an ampersand is never a split.
            "Simon & Garfunkel", "Mumford & Sons", "Nick & Simon", "Earth, Wind & Fire",
            "AC/DC", "Years & Years", "Florence + the Machine",
            // "ft" only counts as a word, never inside one.
            "Fatboy Slim", "Daft Punk", "Left Boy",
    })
    void neverSplitsAnythingButFeaturing(String tag) {
        ArtistTagParser.Credits credits = ArtistTagParser.parse(tag);

        assertEquals(tag, credits.primary());
        assertTrue(credits.featured().isEmpty());
    }

    @Test
    void splitsSeveralGuestsInsideTheFeaturedPart() {
        ArtistTagParser.Credits credits = ArtistTagParser.parse("David Guetta feat. Sia & Fetty Wap");

        assertEquals("David Guetta", credits.primary());
        assertEquals(List.of("Sia", "Fetty Wap"), credits.featured());
    }

    @Test
    void keepsATagThatIsNothingButAFeatureAsIs() {
        ArtistTagParser.Credits credits = ArtistTagParser.parse("feat. Sean Paul");

        assertEquals("feat. Sean Paul", credits.primary());
        assertTrue(credits.featured().isEmpty());
    }

    @Test
    void dropsAGuestThatRepeatsThePrimaryArtist() {
        ArtistTagParser.Credits credits = ArtistTagParser.parse("Anouk feat. anouk");

        assertEquals("Anouk", credits.primary());
        assertTrue(credits.featured().isEmpty());
    }

    @Test
    void handlesBlankAndNullTags() {
        assertEquals(null, ArtistTagParser.primary(null));
        assertEquals(null, ArtistTagParser.primary("  "));
    }

    @Test
    void primaryReturnsTheArtistWithoutGuests() {
        assertEquals("Mark Mancina", ArtistTagParser.primary("Mark Mancina feat. Phil Collins"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Victoria Justice & Elizabeth Gillies|Victoria Justice,Elizabeth Gillies",
            "Sean Paul & David Guetta & Becky G|Sean Paul,David Guetta,Becky G",
            "Marco Borsato;Guus Meeuwis|Marco Borsato,Guus Meeuwis",
            "Earth, Wind & Fire|Earth,Wind,Fire",
            "Alan Walker / Ava Max|Alan Walker,Ava Max",
            "Anouk + Anouk|Anouk",
    })
    void collaboratorsSplitsOnEveryCollaborationSeparator(String name, String expected) {
        assertEquals(List.of(expected.split(",")), ArtistTagParser.collaborators(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Adele", "R&B Allstars", "Florence+the Machine", "AC/", ", Adele"})
    void collaboratorsKeepsANameWithoutACleanSplitWhole(String name) {
        assertEquals(List.of(name.strip()), ArtistTagParser.collaborators(name));
    }
}
