package app.ister.server.nfo;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void parseShowSuccessful () {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("/nfo/tvshow.nfo");
        var subject = Parser.parseShow(resourceAsStream).orElseThrow();
        assertEquals("Star Trek: Discovery", subject.getTitle());
        assertEquals("Follow the voyages of Starfleet on their missions to discover new worlds and new life forms, and one Starfleet officer who must learn that to truly understand all things alien, you must first understand yourself.", subject.getPlot());
        assertEquals(Timestamp.valueOf("2017-09-24 00:00:00.0"), subject.getPremiered());
        assertEquals(List.of("roommate", "self-discovery", "group of friends", "sitcom", "searching for love"), subject.getTags());
    }

    @Test
    void parseShowError() {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("404");
        var subject = Parser.parseShow(resourceAsStream);
        assertTrue(subject.isEmpty());
    }

    @Test
    void parseEpisodeSuccessful () {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("/nfo/episode.nfo");
        var subject = Parser.parseEpisode(resourceAsStream).orElseThrow();
        assertEquals("Filmed Before a Live Studio Audience", subject.getTitle());
        assertEquals("Wanda and Vision struggle to conceal their powers during dinner with Vision’s boss and his wife.", subject.getPlot());
        assertEquals(Timestamp.valueOf("2021-01-15 00:00:00.0"), subject.getAired());
    }

}