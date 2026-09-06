package app.ister.disk.events.subtitleextract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrSubtitleCleanerTest {

    /**
     * Behaves like hunspell: a listed word is accepted in any capitalisation, a name
     * only as listed. Names are the entries that start with a capital.
     */
    static final class FakeLexicon implements Lexicon {
        private final Set<String> entries;

        FakeLexicon(String... words) {
            entries = Set.of(words);
        }

        @Override
        public Set<String> unknown(Collection<String> words) {
            return words.stream().filter(w -> !known(w)).collect(Collectors.toSet());
        }

        private boolean known(String w) {
            if (entries.contains(w)) {
                return true;
            }
            String lower = w.toLowerCase(Locale.ROOT);
            boolean capitalised = w.equals(Character.toUpperCase(w.charAt(0)) + lower.substring(1));
            boolean allCaps = w.equals(w.toUpperCase(Locale.ROOT));
            // hunspell accepts "Just" and "JUST" for the entry "just", but not "thIs".
            return (capitalised && entries.contains(lower)) || (allCaps && entries.stream().anyMatch(e -> e.equalsIgnoreCase(w)));
        }

        @Override
        public boolean isProperNoun(String word) {
            return entries.contains(word) && Character.isUpperCase(word.charAt(0));
        }
    }

    private static final Lexicon ENGLISH = new FakeLexicon(
            "I", "I'm", "It", "Jerry", "Elaine", "Devils", "Job", "Billy", "Pitt", "Jerry's",
            "is", "it", "it's", "with", "me", "you", "look", "like", "that", "going", "to", "get", "a", "sample",
            "giving", "them", "isn't", "pleasure", "just", "go", "along", "idea", "believe", "their", "job",
            "pilot", "still", "the", "here", "home", "physical", "examination", "urine", "always", "this",
            "all", "about", "and", "that's", "sitting", "next");
    private static final Lexicon DUTCH = new FakeLexicon(
            "Jerry", "David", "als", "je", "op", "tv", "een", "ziet", "arrestatie", "jas", "of", "hoed", "z'n", "hij",
            "is", "in", "die", "klokkentoren", "dat", "niet", "wat", "ik", "bedoel", "doen", "ja", "zo",
            "nooit", "zei", "jullie", "serie", "series", "van", "mij", "ook", "dit", "idee", "iemand", "mooie", "zit", "bang");

    private final OcrSubtitleCleaner rulesOnly = new OcrSubtitleCleaner();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Do you know what this Is all about?      | Do you know what this is all about?",
            "Not one person here Is home.             | Not one person here is home.",
            "Look at it. It's too high.               | Look at it. It's too high.",
            "Is it decaf?                             | Is it decaf?",
            "-It is. -Is it?                          | -It is. -Is it?",
            "\"Did you re--? I can't find him.        | \"Did you re--? I can't find him.",
            "Yes, It was purple. I liked It.          | Yes, it was purple. I liked it.",
            "Glving them that, Isn't It a pleasure?   | Glving them that, isn't it a pleasure?",
            "Just go along with it.                   | Just go along with it.",
            "THIS IS IT.                              | THIS IS IT.",
    })
    void functionWordRuleNeedsNoDictionary(String input, String expected) {
        assertEquals(expected.trim(), rulesOnly.clean(input.trim(), "eng"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // l read for i inside a word
            "I'm golng to get a urlne sample.         | I'm going to get a urine sample.",
            "I belleve thelr pliot Is stlil here.     | I believe their pilot is still here.",
            // a capital I/J opening a common word mid-sentence, but not a name
            "You Just look Ilke that. lt's wlth me.   | You just look like that. It's with me.",
            "That's a great Idea, Jerry.              | That's a great idea, Jerry.",
            "Elalne and the Devlis, Bllly.            | Elaine and the Devils, Billy.",
            "It's a Job for Jerry.                    | It's a Job for Jerry.",
            "I'm here. I'm home.                      | I'm here. I'm home.",
            // sentence starts keep their capital, all-caps and ambiguous words are left alone
            "Just go along with it. Believe me.       | Just go along with it. Believe me.",
            "THIS IS IT. Lllke that.                  | THIS IS IT. Lllke that.",
            "Ilke that.                               | Like that.",
            "It's PItt, thIs is Jerry's job.          | It's Pitt, this is Jerry's job.",
            "sitting next to me, ’Just like that’     | sitting next to me, ’just like that’",
    })
    void dictionaryRepairsEnglishMisreads(String input, String expected) {
        assertEquals(expected.trim(), rulesOnly.clean(input.trim(), "eng", ENGLISH));
    }

    @Test
    void secondLineContinuesTheSentence() {
        assertEquals("Not one person here is home.\nWe're all out.",
                rulesOnly.clean("Not one person here Is home.\nWe're all out.", "eng"));
        assertEquals("There's a button and\nit's missing.",
                rulesOnly.clean("There's a button and\nIt's missing.", "eng"));
        assertEquals("He was late.\nIt happens.",
                rulesOnly.clean("He was late.\nIt happens.", "eng"));
        assertEquals("You look\nlike that.",
                rulesOnly.clean("You look\nIlke that.", "eng", ENGLISH));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Als Je op tv een arrestatie ziet         | Als je op tv een arrestatie ziet",
            "een krant, z'n Jas of z'n hoed.          | een krant, z'n jas of z'n hoed.",
            "dat hij bang Is...                       | dat hij bang is...",
            "Ik bedoel: Is hij soms bang dat hij      | Ik bedoel: Is hij soms bang dat hij",
            "HIJ zit In dle klokkentoren              | HIJ zit in die klokkentoren",
            "Dat ls nlet wat lk bedoel.               | Dat is niet wat ik bedoel.",
            "Nlet doen.                               | Niet doen.",
            "Ja, dat is zo.                           | Ja, dat is zo.",
            "Jullle hebben noolt een Idee, zel Jerry. | Jullie hebben nooit een idee, zei Jerry.",
            "Davld heeft moole serles, Iemand?        | David heeft mooie series, iemand?",
            "DIt is van mIJ, zei Jerry. DIt ook.      | Dit is van mij, zei Jerry. Dit ook.",
    })
    void dictionaryRepairsDutchMisreads(String input, String expected) {
        assertEquals(expected.trim(), rulesOnly.clean(input.trim(), "nld", DUTCH));
    }

    @Test
    void unknownLanguageIsLeftAlone() {
        assertEquals("das Ist nlcht", rulesOnly.clean("das Ist nlcht", "deu"));
    }

    @Test
    void variantsEnumerateEveryIlReading() {
        Set<String> v = OcrSubtitleCleaner.variants("belleve");
        assertTrue(v.contains("believe"));
        assertTrue(v.contains("beileve"));
        assertTrue(!v.contains("belleve"));
        assertTrue(OcrSubtitleCleaner.variants("devlis").contains("devils"));
        assertTrue(OcrSubtitleCleaner.variants("ilke").contains("like"));
        assertTrue(OcrSubtitleCleaner.variants("illiililil").isEmpty(), "too many positions: skipped");
    }

    @Test
    void cleanFileRewritesCueTextsOnlyAndRunsTheDictionaryOncePerFile(@TempDir Path dir) throws IOException {
        Path srt = dir.resolve("x.srt");
        Files.writeString(srt, """
                1
                00:00:02,480 --> 00:00:04,050
                I'm golng to get
                a physical examination.

                2
                00:00:05,800 --> 00:00:08,840
                That urlne sample. Glving them that,
                that's always a pleasure, Isn't It?
                """);
        int[] calls = {0};
        Lexicon counting = new Lexicon() {
            @Override
            public Set<String> unknown(Collection<String> words) {
                calls[0]++;
                return ENGLISH.unknown(words);
            }

            @Override
            public boolean isProperNoun(String word) {
                return ENGLISH.isProperNoun(word);
            }
        };
        HunspellLexicons lexicons = new HunspellLexicons() {
            @Override
            public Optional<Lexicon> forLanguage(String lang) {
                return Optional.of(counting);
            }
        };

        new OcrSubtitleCleaner(lexicons).cleanFile(srt, "eng");

        assertEquals("""
                1
                00:00:02,480 --> 00:00:04,050
                I'm going to get
                a physical examination.

                2
                00:00:05,800 --> 00:00:08,840
                That urine sample. Giving them that,
                that's always a pleasure, isn't it?
                """, Files.readString(srt));
        assertEquals(1, calls[0]);
    }

    @Test
    void cleanFileHandlesSubtileOcrDoubleBlankLinesAndCrlf(@TempDir Path dir) throws IOException {
        // subtile-ocr writes an extra blank line between cues; every cue after the first must still be cleaned.
        Path srt = dir.resolve("x.srt");
        Files.writeString(srt, """
                1
                00:00:02,280 --> 00:00:04,369
                Do you know what this Is all about?


                2
                00:00:06,000 --> 00:00:06,860
                To be out. This Is out.


                3
                00:00:07,160 --> 00:00:10,630
                Out Is one of the single most
                enjoyable things.


                """.replace("\n", "\r\n"));

        rulesOnly.cleanFile(srt, "eng");

        assertEquals("""
                1
                00:00:02,280 --> 00:00:04,369
                Do you know what this is all about?

                2
                00:00:06,000 --> 00:00:06,860
                To be out. This is out.

                3
                00:00:07,160 --> 00:00:10,630
                Out is one of the single most
                enjoyable things.
                """, Files.readString(srt));
    }
}
