package app.ister.disk.scanner;

import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicPathObjectTest {

    private static final String ROOT = "/music";

    @Test
    void artistDirectory() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles");
        assertEquals(DirType.ARTIST, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
        assertEquals("The Beatles", subject.getArtistName());
    }

    @Test
    void albumDirectoryWithYear() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/Abbey Road (1969)");
        assertEquals(DirType.ALBUM, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
        assertEquals("The Beatles", subject.getArtistName());
        assertEquals("Abbey Road", subject.getAlbumName());
        assertEquals(1969, subject.getAlbumYear());
    }

    @Test
    void albumDirectoryWithoutYear() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/Abbey Road");
        assertEquals(DirType.ALBUM, subject.getDirType());
        assertEquals("Abbey Road", subject.getAlbumName());
        assertEquals(0, subject.getAlbumYear());
    }

    @ParameterizedTest
    @CsvSource({
        "/music/The Beatles/Abbey Road (1969)/01 - Come Together.flac, 1, 1",
        "/music/The Beatles/Abbey Road (1969)/02 - Something.mp3, 1, 2",
        "/music/The Beatles/Abbey Road (1969)/11 - The End.wav, 1, 11"
    })
    void audioFileInAlbumDirectory(String path, int disc, int track) {
        var subject = new MusicPathObject(ROOT, path);
        assertEquals(DirType.ALBUM, subject.getDirType());
        assertEquals(FileType.AUDIO, subject.getFileType());
        assertEquals("The Beatles", subject.getArtistName());
        assertEquals("Abbey Road", subject.getAlbumName());
        assertEquals(1969, subject.getAlbumYear());
        assertEquals(disc, subject.getDiscNumber());
        assertEquals(track, subject.getTrackNumber());
    }

    @ParameterizedTest
    @CsvSource({
        "/music/Artist/Album/01 - Track.ogg",
        "/music/Artist/Album/02 - Track.aac",
        "/music/Artist/Album/03 - Track.m4a",
        "/music/Artist/Album/04 - Track.opus",
        "/music/Artist/Album/05 - Track.wma"
    })
    void audioFileVariousExtensions(String path) {
        var subject = new MusicPathObject(ROOT, path);
        assertEquals(FileType.AUDIO, subject.getFileType());
    }

    @Test
    void multiDiscTrack() {
        var subject = new MusicPathObject(ROOT, "/music/Artist/Album/1-01 - Track.flac");
        assertEquals(FileType.AUDIO, subject.getFileType());
        assertEquals(1, subject.getDiscNumber());
        assertEquals(1, subject.getTrackNumber());
    }

    @Test
    void artistNfoFile() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/artist.nfo");
        assertEquals(DirType.ARTIST, subject.getDirType());
        assertEquals(FileType.NFO, subject.getFileType());
        assertEquals("The Beatles", subject.getArtistName());
    }

    @Test
    void albumNfoFile() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/Abbey Road (1969)/album.nfo");
        assertEquals(DirType.ALBUM, subject.getDirType());
        assertEquals(FileType.NFO, subject.getFileType());
        assertEquals("Abbey Road", subject.getAlbumName());
    }

    @Test
    void otherNfoFileInAlbumIsIgnored() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/Abbey Road (1969)/info.nfo");
        assertEquals(FileType.NONE, subject.getFileType());
    }

    @Test
    void artistImageFile() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/artist.jpg");
        assertEquals(DirType.ARTIST, subject.getDirType());
        assertEquals(FileType.IMAGE, subject.getFileType());
    }

    @Test
    void folderImageForArtist() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/folder.jpg");
        assertEquals(DirType.ARTIST, subject.getDirType());
        assertEquals(FileType.IMAGE, subject.getFileType());
    }

    @Test
    void coverImageInAlbumDirectory() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/Abbey Road (1969)/cover.jpg");
        assertEquals(DirType.ALBUM, subject.getDirType());
        assertEquals(FileType.IMAGE, subject.getFileType());
    }

    @Test
    void folderImageInAlbumDirectory() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/Abbey Road (1969)/folder.png");
        assertEquals(DirType.ALBUM, subject.getDirType());
        assertEquals(FileType.IMAGE, subject.getFileType());
    }

    @Test
    void unknownFileInAlbumDirectoryIsIgnored() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/Abbey Road (1969)/readme.txt");
        assertEquals(FileType.NONE, subject.getFileType());
    }

    @Test
    void audioFileDirectlyInSingleFolder() {
        // Flat layout: {root}/{AlbumName (year)}/NN - Track.flac — no separate artist folder.
        var subject = new MusicPathObject(ROOT, "/music/Grease_ Soundtrack (1991)/01-Grease.flac");
        assertEquals(DirType.ARTIST, subject.getDirType());
        assertEquals(FileType.AUDIO, subject.getFileType());
        assertEquals("Grease_ Soundtrack", subject.getAlbumName());
        assertEquals(1991, subject.getAlbumYear());
        assertEquals(1, subject.getTrackNumber());
        assertEquals(1, subject.getDiscNumber());
        assertTrue(subject.isFlatAlbumStructure());
    }

    @ParameterizedTest
    @CsvSource({
        "/music/Grease_ Soundtrack (1991)/01-Grease.flac, 1",
        "/music/Grease_ Soundtrack (1991)/02-Summer Nights.flac, 2",
        "/music/Grease_ Soundtrack (1991)/03-Hopelessly Devoted to You.flac, 3"
    })
    void trackNumberParsedInFlatStructure(String path, int expectedTrackNumber) {
        var subject = new MusicPathObject(ROOT, path);
        assertEquals(FileType.AUDIO, subject.getFileType());
        assertEquals(expectedTrackNumber, subject.getTrackNumber());
        assertTrue(subject.isFlatAlbumStructure());
    }

    @Test
    void normalStructureIsNotFlatAlbumStructure() {
        var subject = new MusicPathObject(ROOT, "/music/The Beatles/Abbey Road (1969)/01 - Come Together.flac");
        assertEquals(FileType.AUDIO, subject.getFileType());
        assertEquals(1, subject.getTrackNumber());
        assertFalse(subject.isFlatAlbumStructure());
    }

    @ParameterizedTest
    @CsvSource({
        "/music/Ye Banished Privateers/The Legend of Libertalia/Ye Banished Privateers - The Legend of Libertalia - 01 Bring out Your Dead.flac, 1, 1",
        "/music/Ye Banished Privateers/The Legend of Libertalia/Ye Banished Privateers - The Legend of Libertalia - 02 You and Me.flac, 1, 2",
        "/music/Ye Banished Privateers/The Legend of Libertalia/Ye Banished Privateers - The Legend of Libertalia - 10 Fisher Lass.flac, 1, 10"
    })
    void trackNumberFromLastSegmentAfterDash(String path, int disc, int track) {
        var subject = new MusicPathObject(ROOT, path);
        assertEquals(FileType.AUDIO, subject.getFileType());
        assertEquals(disc, subject.getDiscNumber());
        assertEquals(track, subject.getTrackNumber());
    }

    @Test
    void pathOutsideLibraryRootIsNone() {
        var subject = new MusicPathObject(ROOT, "/other/Artist/Album/track.mp3");
        assertEquals(DirType.NONE, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
    }

    @Test
    void libraryRootWithTrailingSlash() {
        var subject = new MusicPathObject("/music/", "/music/The Beatles");
        assertEquals(DirType.ARTIST, subject.getDirType());
        assertEquals("The Beatles", subject.getArtistName());
    }
}
