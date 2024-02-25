package app.ister.server.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathObjectTest {

    @Test
    void showTest() {
        var subject = new PathObject("/disk/shows/Show (2024)");
        assertEquals(2024, subject.getShowYear());
        assertEquals("Show", subject.getShow());
        assertEquals(0, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(PathType.SHOW, subject.getType());
    }

    @Test
    void nfoFileForShowTest() {
        var subject = new PathObject("/disk/shows/Show (2024)");
        assertEquals(2024, subject.getShowYear());
        assertEquals("Show", subject.getShow());
        assertEquals(0, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(PathType.SHOW, subject.getType());
    }

    @Test
    void seasonTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 01");
        assertEquals(2024, subject.getShowYear());
        assertEquals("Show", subject.getShow());
        assertEquals(1, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(PathType.SEASON, subject.getType());
    }

    @Test
    void episodeTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/s01e12.mkv");
        assertEquals(2024, subject.getShowYear());
        assertEquals("Show", subject.getShow());
        assertEquals(1, subject.getSeason());
        assertEquals(12, subject.getEpisode());
        assertEquals(PathType.EPISODE, subject.getType());
    }

    @Test
    void episodeInSeasonDirTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 01/s01e12.mkv");
        assertEquals(2024, subject.getShowYear());
        assertEquals("Show", subject.getShow());
        assertEquals(1, subject.getSeason());
        assertEquals(12, subject.getEpisode());
        assertEquals(PathType.EPISODE, subject.getType());
    }

    @Test
    void nfoFileForEpisodeTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 01/s01e12.nfo");
        assertEquals(2024, subject.getShowYear());
        assertEquals("Show", subject.getShow());
        assertEquals(1, subject.getSeason());
        assertEquals(12, subject.getEpisode());
        assertEquals(PathType.EPISODE, subject.getType());
    }
}