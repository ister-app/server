package app.ister.disk.nfo;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class ParserTest {

    @Test
    void parseShowSuccessful() {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("/nfo/tvshow.nfo");
        var subject = Parser.parseShow(resourceAsStream).orElseThrow();
        assertEquals("Star Trek: Discovery", subject.getTitle());
        assertEquals("Follow the voyages of Starfleet on their missions to discover new worlds and new life forms, and one Starfleet officer who must learn that to truly understand all things alien, you must first understand yourself.", subject.getPlot());
        assertEquals(LocalDate.parse("2017-09-24"), subject.getPremiered());
        assertEquals(List.of("roommate", "self-discovery", "group of friends", "sitcom", "searching for love"), subject.getTags());
    }

    @Test
    void parseShowError() {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("404");
        var subject = Parser.parseShow(resourceAsStream);
        assertTrue(subject.isEmpty());
    }

    @Test
    void parseEpisodeSuccessful() {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("/nfo/episode.nfo");
        var subject = Parser.parseEpisode(resourceAsStream).orElseThrow();
        assertEquals("Filmed Before a Live Studio Audience", subject.getTitle());
        assertEquals("Wanda and Vision struggle to conceal their powers during dinner with Vision\u2019s boss and his wife.", subject.getPlot());
        assertEquals(LocalDate.parse("2021-01-15"), subject.getAired());
    }

    @Test
    void parseArtistSuccessful() {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("/nfo/artist.nfo");
        var subject = Parser.parseArtist(resourceAsStream).orElseThrow();
        assertEquals("The Beatles", subject.getName());
        assertEquals("Beatles, The", subject.getSortname());
        assertEquals("The Beatles were an English rock band formed in Liverpool in 1960.", subject.getBiography());
        assertEquals("Rock", subject.getGenre());
        assertEquals("1960-1970", subject.getYearsactive());
    }

    @Test
    void parseArtistError() {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("404");
        var subject = Parser.parseArtist(resourceAsStream);
        assertTrue(subject.isEmpty());
    }

    @Test
    void parseAlbumSuccessful() {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("/nfo/album.nfo");
        var subject = Parser.parseAlbum(resourceAsStream).orElseThrow();
        assertEquals("Abbey Road", subject.getTitle());
        assertEquals("The eleventh studio album by the English rock band the Beatles.", subject.getReview());
        assertEquals("Rock", subject.getGenre());
        assertEquals("Apple Records", subject.getLabel());
        assertEquals(1969, subject.getYear());
        assertEquals(LocalDate.parse("1969-09-26"), subject.getReleasedate());
    }

    @Test
    void parseAlbumMinimal() {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("/nfo/album_minimal.nfo");
        var subject = Parser.parseAlbum(resourceAsStream).orElseThrow();
        assertEquals("Minimal Album", subject.getTitle());
        assertNull(subject.getReview());
        assertNull(subject.getReleasedate());
        assertEquals(0, subject.getYear());
    }

    @Test
    void parseAlbumError() {
        InputStream resourceAsStream = ParserTest.class.getResourceAsStream("404");
        var subject = Parser.parseAlbum(resourceAsStream);
        assertTrue(subject.isEmpty());
    }

}
