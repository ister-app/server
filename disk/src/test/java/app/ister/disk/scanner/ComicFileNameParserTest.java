package app.ister.disk.scanner;

import app.ister.disk.scanner.ComicFileNameParser.ComicName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComicFileNameParserTest {

    @Test
    void parsesAVolNumber() {
        ComicName parsed = ComicFileNameParser.parse("attackontitan_vol27");

        assertEquals("attackontitan_vol27", parsed.identityName());
        assertEquals(27.0, parsed.number());
        assertEquals("Volume 27", parsed.displayTitle());
    }

    @Test
    void parsesAnIssueNumber() {
        ComicName parsed = ComicFileNameParser.parse("rickandmorty2023_issue8");

        assertEquals(8.0, parsed.number());
        assertEquals("Issue 8", parsed.displayTitle());
    }

    @Test
    void parsesAHashNumber() {
        ComicName parsed = ComicFileNameParser.parse("Saga #12");

        assertEquals(12.0, parsed.number());
        assertEquals("Issue 12", parsed.displayTitle());
    }

    @Test
    void tokensAfterTheNumberBecomeTheSubtitle() {
        ComicName parsed = ComicFileNameParser.parse("rickandmorty2023_vol1_thespaceshakesagapart1");

        assertEquals(1.0, parsed.number());
        assertEquals("thespaceshakesagapart1", parsed.displayTitle());
    }

    /** "...part2-1.pdf" is a re-download of "...part2.pdf": same identity, one volume row. */
    @Test
    void dedupeSuffixIsStrippedFromTheIdentity() {
        ComicName original = ComicFileNameParser.parse("rickandmorty2023_vol2_thespaceshakesagapart2");
        ComicName duplicate = ComicFileNameParser.parse("rickandmorty2023_vol2_thespaceshakesagapart2-1");

        assertEquals(original.identityName(), duplicate.identityName());
    }

    /** A bare trailing number is not a dedupe suffix — "issue8" must stay "issue8". */
    @Test
    void aBareTrailingNumberIsNotADedupeSuffix() {
        assertEquals("rickandmorty2023_issue8",
                ComicFileNameParser.parse("rickandmorty2023_issue8").identityName());
    }

    @Test
    void documentedConventionParses() {
        assertEquals(27.0, ComicFileNameParser.parse("Volume 27").number());
        assertEquals("Ondertitel", ComicFileNameParser.parse("Vol 3 - Ondertitel").displayTitle());
        assertEquals(8.0, ComicFileNameParser.parse("Issue 8").number());
    }

    @Test
    void fractionalVolumeNumbersAreKept() {
        ComicName parsed = ComicFileNameParser.parse("vol 1.5");

        assertEquals(1.5, parsed.number());
        assertEquals("Volume 1.5", parsed.displayTitle());
    }

    @Test
    void trailingDigitsCountAsAVolumeNumber() {
        ComicName parsed = ComicFileNameParser.parse("fairytail 3");

        assertEquals(3.0, parsed.number());
        assertEquals("fairytail 3", parsed.displayTitle());
    }

    /** No number at all: identity as title, position unknown (orders last). */
    @Test
    void nameWithoutANumberHasNullNumber() {
        ComicName parsed = ComicFileNameParser.parse("some_story");

        assertNull(parsed.number());
        assertEquals("some story", parsed.displayTitle());
    }
}
