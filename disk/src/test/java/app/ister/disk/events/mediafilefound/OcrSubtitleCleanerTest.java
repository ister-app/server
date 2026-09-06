package app.ister.disk.events.mediafilefound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OcrSubtitleCleanerTest {

    private final OcrSubtitleCleaner cleaner = new OcrSubtitleCleaner();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Do you know what this Is all about?      | Do you know what this is all about?",
            "Not one person here Is home.             | Not one person here is home.",
            "Look at it. It's too high.               | Look at it. It's too high.",
            "Is it decaf?                             | Is it decaf?",
            "-It is. -Is it?                          | -It is. -Is it?",
            "\"Did you re--? I can't find him.        | \"Did you re--? I can't find him.",
            "Yes, It was purple. I liked It.          | Yes, it was purple. I liked it.",
            "I'm golng to get a urlne sample.         | I'm going to get a urlne sample.",
            "Glving them that, Isn't It a pleasure?   | Glving them that, isn't it a pleasure?",
            "You look Ilke that. lt's wlth me.        | You look Ilke that. It's with me.",
            "Just go along with it.                   | Just go along with it.",
            "THIS IS IT.                              | THIS IS IT.",
    })
    void cleansEnglishMisreads(String input, String expected) {
        assertEquals(expected.trim(), cleaner.clean(input.trim(), "eng"));
    }

    @Test
    void secondLineContinuesTheSentence() {
        assertEquals("Not one person here is home.\nWe're all out.",
                cleaner.clean("Not one person here Is home.\nWe're all out.", "eng"));
        assertEquals("There's a button and\nit's missing.",
                cleaner.clean("There's a button and\nIt's missing.", "eng"));
        assertEquals("He was late.\nIt happens.",
                cleaner.clean("He was late.\nIt happens.", "eng"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Als Je op tv een arrestatie ziet         | Als je op tv een arrestatie ziet",
            "een krant, z'n Jas of z'n hoed.          | een krant, z'n Jas of z'n hoed.",
            "dat hij bang Is...                       | dat hij bang is...",
            "Ik bedoel: Is hij soms bang dat hij      | Ik bedoel: Is hij soms bang dat hij",
            "HIJ zit In dle klokkentoren              | HIJ zit in die klokkentoren",
            "Dat ls nlet wat lk bedoel.               | Dat is niet wat ik bedoel.",
            "Nlet doen.                               | Niet doen.",
            "Ja, dat is zo.                           | Ja, dat is zo.",
    })
    void cleansDutchMisreads(String input, String expected) {
        assertEquals(expected.trim(), cleaner.clean(input.trim(), "nld"));
    }

    @Test
    void unknownLanguageIsLeftAlone() {
        assertEquals("das Ist nlcht", cleaner.clean("das Ist nlcht", "deu"));
    }

    @Test
    void cleanFileRewritesCueTextsOnly(@TempDir Path dir) throws IOException {
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

        cleaner.cleanFile(srt, "eng");

        assertEquals("""
                1
                00:00:02,480 --> 00:00:04,050
                I'm going to get
                a physical examination.

                2
                00:00:05,800 --> 00:00:08,840
                That urlne sample. Glving them that,
                that's always a pleasure, isn't it?
                """, Files.readString(srt));
    }
}
