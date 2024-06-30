package app.ister.server.events.subtitlefilefound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubtitleFilePathParserTest {

    @Test
    void langCodeToIso3() {
        assertEquals("nld", SubtitleFilePathParser.langCodeToIso3("/mnt/shows/Show (2018)/Season 01/s01e01.nl.srt"));
        assertEquals("nld", SubtitleFilePathParser.langCodeToIso3("/mnt/shows/Show (2018)/Season 01/s01e01.NlD.srt"));
        assertEquals("fra", SubtitleFilePathParser.langCodeToIso3("/mnt/shows/Show (2018)/Season 01/s01e01.fr.srt"));
        assertEquals("fra", SubtitleFilePathParser.langCodeToIso3("/mnt/shows/Show (2018)/Season 01/s01e01.FRa.srt"));
    }

    @Test
    void mediaFileAndSubtitleFileBelongTogether() {
        assertTrue(SubtitleFilePathParser.mediaFileAndSubtitleFileBelongTogether(
                "/mnt/shows/Show (2018)/Season 01/s01e01.mkv",
                "/mnt/shows/Show (2018)/Season 01/s01e01.en.srt"
        ));

        assertFalse(SubtitleFilePathParser.mediaFileAndSubtitleFileBelongTogether(
                "/mnt/shows/Show (2018)/Season 01/s01e02.mkv",
                "/mnt/shows/Show (2018)/Season 01/s01e01.en.srt"
        ));

        assertFalse(SubtitleFilePathParser.mediaFileAndSubtitleFileBelongTogether(
                "/mnt/shows/Show 2 (2020)/Season 01/s01e01.mkv",
                "/mnt/shows/Show (2018)/Season 01/s01e01.en.srt"
        ));
    }
}