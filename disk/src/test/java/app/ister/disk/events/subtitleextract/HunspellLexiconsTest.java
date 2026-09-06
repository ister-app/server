package app.ister.disk.events.subtitleextract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against the real hunspell when it and the en_US/nl_NL dictionaries are installed (as in CI and the images). */
class HunspellLexiconsTest {

    private static final String DIR = "/usr/share/hunspell";

    @Test
    void readsProperNounsFromTheRawDictionary(@TempDir Path dir) throws IOException {
        Path dic = dir.resolve("t.dic");
        Files.writeString(dic, "4\nJerry/M\njust\nI'm\nIt\tph:it\n", StandardCharsets.UTF_8);
        Set<String> nouns = HunspellLexicons.readProperNouns(dic, StandardCharsets.UTF_8);
        assertEquals(Set.of("Jerry", "I'm", "It"), nouns);
    }

    @Test
    void unconfiguredOrMissingDictionaryIsEmpty(@TempDir Path dir) {
        HunspellLexicons lexicons = new HunspellLexicons("hunspell", dir.toString(), "eng=en_US");
        assertTrue(lexicons.forLanguage("eng").isEmpty(), "no .dic in the dir");
        assertTrue(lexicons.forLanguage("nld").isEmpty(), "not configured");
    }

    @Test
    void spellsEnglishAndDutchThroughHunspell() {
        assumeTrue(Files.exists(Path.of(DIR, "en_US.dic")) && Files.exists(Path.of(DIR, "nl_NL.dic")), "hunspell dictionaries installed");
        HunspellLexicons lexicons = new HunspellLexicons("hunspell", DIR, "eng=en_US,nld=nl_NL");
        Optional<Lexicon> eng = lexicons.forLanguage("eng");
        assumeTrue(eng.isPresent(), "hunspell binary installed");

        Set<String> unknown = eng.get().unknown(List.of("believe", "belleve", "Just", "Jerry", "jerry", "it's", "Elaine"));
        assertEquals(Set.of("belleve", "jerry"), unknown);
        assertTrue(eng.get().isProperNoun("Jerry"));
        assertTrue(eng.get().isProperNoun("I'm"));
        assertFalse(eng.get().isProperNoun("Just"));

        Lexicon nld = lexicons.forLanguage("nld").orElseThrow();
        assertEquals(Set.of("noolt"), nld.unknown(List.of("nooit", "noolt", "Jullie", "jullie")));
        assertFalse(nld.isProperNoun("Jullie"));
    }
}
