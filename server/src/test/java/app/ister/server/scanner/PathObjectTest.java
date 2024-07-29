package app.ister.server.scanner;

import app.ister.server.scanner.enums.DirType;
import app.ister.server.scanner.enums.FileType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathObjectTest {

    @Test
    void notCorrectPath() {
        var subject = new PathObject("/disk/shows");
        assertEquals(0, subject.getYear());
        assertNull(subject.getName());
        assertEquals(0, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(DirType.NONE, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
    }

    @Test
    void notCorrectMissingShowPath() {
        var subject = new PathObject("/disk/shows/Season 08/s08e08.mkv");
        assertEquals(0, subject.getYear());
        assertNull(subject.getName());
        assertEquals(0, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(DirType.NONE, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
    }

    @Test
    void movieTest() {
        var subject = new PathObject("/disk/movies/Movie (2024).mkv");
        assertEquals(2024, subject.getYear());
        assertEquals("Movie", subject.getName());
        assertEquals(0, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(DirType.MOVIE, subject.getDirType());
        assertEquals(FileType.MEDIA, subject.getFileType());
    }

    @Test
    void imageFileFormovieTest() {
        var subject = new PathObject("/disk/movies/Movie (2024)-thumb.jpg");
        assertEquals(2024, subject.getYear());
        assertEquals("Movie", subject.getName());
        assertEquals(0, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(DirType.MOVIE, subject.getDirType());
        assertEquals(FileType.IMAGE, subject.getFileType());
    }

    @Test
    void showTest() {
        var subject = new PathObject("/disk/shows/Show (2024)");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(0, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(DirType.SHOW, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
    }

    @Test
    void coverShowTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/cover.png");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(0, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(DirType.SHOW, subject.getDirType());
        assertEquals(FileType.IMAGE, subject.getFileType());
    }

    @Test
    void nfoFileForShowTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/tvshow.nfo");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(0, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(DirType.SHOW, subject.getDirType());
        assertEquals(FileType.NFO, subject.getFileType());
    }

    @Test
    void seasonTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 01");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(1, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(DirType.SEASON, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
    }

    @Test
    void imageSeasonTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 01/cover.png");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(1, subject.getSeason());
        assertEquals(0, subject.getEpisode());
        assertEquals(DirType.SEASON, subject.getDirType());
        assertEquals(FileType.IMAGE, subject.getFileType());
    }

    @Test
    void episodeTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/s01e12.mkv");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(1, subject.getSeason());
        assertEquals(12, subject.getEpisode());
        assertEquals(DirType.EPISODE, subject.getDirType());
        assertEquals(FileType.MEDIA, subject.getFileType());
    }

    @Test
    void episodeInSeasonDirTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 02/s02e99.mkv");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(2, subject.getSeason());
        assertEquals(99, subject.getEpisode());
        assertEquals(DirType.EPISODE, subject.getDirType());
        assertEquals(FileType.MEDIA, subject.getFileType());
    }

    @Test
    void episodeWithCapitolInSeasonDirTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 05/S05E05.mkv");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(5, subject.getSeason());
        assertEquals(5, subject.getEpisode());
        assertEquals(DirType.EPISODE, subject.getDirType());
        assertEquals(FileType.MEDIA, subject.getFileType());
    }

    @Test
    void nfoFileForEpisodeTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 02/s02e13.nfo");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(2, subject.getSeason());
        assertEquals(13, subject.getEpisode());
        assertEquals(DirType.EPISODE, subject.getDirType());
        assertEquals(FileType.NFO, subject.getFileType());
    }

    @Test
    void imageFileForEpisodeTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 01/s01e12-thumb.jpg");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(1, subject.getSeason());
        assertEquals(12, subject.getEpisode());
        assertEquals(DirType.EPISODE, subject.getDirType());
        assertEquals(FileType.IMAGE, subject.getFileType());
    }

    @Test
    void subtitleFileForEpisodeTest() {
        var subject = new PathObject("/disk/shows/Show (2024)/Season 01/s01e12.en.srt");
        assertEquals(2024, subject.getYear());
        assertEquals("Show", subject.getName());
        assertEquals(1, subject.getSeason());
        assertEquals(12, subject.getEpisode());
        assertEquals(DirType.EPISODE, subject.getDirType());
        assertEquals(FileType.SUBTITLE, subject.getFileType());
    }
}