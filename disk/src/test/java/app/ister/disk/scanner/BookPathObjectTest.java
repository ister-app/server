package app.ister.disk.scanner;

import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookPathObjectTest {

    private static final String ROOT = "/books";

    @Test
    void authorDirectory() {
        var subject = new BookPathObject(ROOT, "/books/Tommy Wieringa");
        assertEquals(DirType.ARTIST, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
        assertEquals("Tommy Wieringa", subject.getAuthorName());
        assertEquals(0, subject.getAuthorYear());
    }

    @Test
    void authorDirectoryWithBirthYear() {
        var subject = new BookPathObject(ROOT, "/books/John Flanagan (1944)");
        assertEquals(DirType.ARTIST, subject.getDirType());
        assertEquals("John Flanagan", subject.getAuthorName());
        assertEquals(1944, subject.getAuthorYear());
    }

    @Test
    void epubDirectlyUnderAuthor() {
        var subject = new BookPathObject(ROOT, "/books/John Flanagan (1944)/De Grijze Jager - De brandende brug.epub");
        assertEquals(FileType.EPUB, subject.getFileType());
        assertEquals("John Flanagan", subject.getAuthorName());
        assertEquals(1944, subject.getAuthorYear());
        assertEquals("De Grijze Jager - De brandende brug", subject.getBookName());
        assertEquals(0, subject.getBookYear());
    }

    @Test
    void karaokeSuffixIsStrippedFromBookNameOnly() {
        var subject = new BookPathObject(ROOT, "/books/Tommy Wieringa/Dit zijn de namen (karaoke).epub");
        assertEquals(FileType.EPUB, subject.getFileType());
        assertEquals("Dit zijn de namen", subject.getBookName());
    }

    @Test
    void karaokeSuffixWithYearIsStrippedInAnyOrder() {
        var subject = new BookPathObject(ROOT, "/books/Tommy Wieringa/Dit zijn de namen (2012) (karaoke).epub");
        assertEquals("Dit zijn de namen", subject.getBookName());
        assertEquals(2012, subject.getBookYear());
    }

    @Test
    void bookDirectory() {
        var subject = new BookPathObject(ROOT, "/books/Tommy Wieringa/Dit zijn de namen");
        assertEquals(DirType.ALBUM, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
        assertEquals("Dit zijn de namen", subject.getBookName());
    }

    @Test
    void zeroBasedUnderscoreChapterNumber() {
        var subject = new BookPathObject(ROOT, "/books/Tommy Wieringa/Dit zijn de namen/000_Dit_zijn_de_namen_door_.mp3");
        assertEquals(DirType.ALBUM, subject.getDirType());
        assertEquals(FileType.AUDIO, subject.getFileType());
        assertEquals("Dit zijn de namen", subject.getBookName());
        assertEquals(0, subject.getChapterNumber());
    }

    @ParameterizedTest
    @CsvSource({
            "005_Hoofdstuk_1_Het_echte_d.mp3, 5",
            "01 - Chapter one.mp3, 1",
            "12. Chapter twelve.mp3, 12",
            "047_Boekgegevens.mp3, 47",
    })
    void chapterNumberFormats(String filename, int expectedNumber) {
        var subject = new BookPathObject(ROOT, "/books/Author/Book/" + filename);
        assertEquals(FileType.AUDIO, subject.getFileType());
        assertEquals(expectedNumber, subject.getChapterNumber());
    }

    @Test
    void coverImageInBookDirectory() {
        var subject = new BookPathObject(ROOT, "/books/Tommy Wieringa/Dit zijn de namen/cover.jpg");
        assertEquals(FileType.IMAGE, subject.getFileType());
        assertEquals(DirType.ALBUM, subject.getDirType());
    }

    @Test
    void albumNfoInBookDirectory() {
        var subject = new BookPathObject(ROOT, "/books/Tommy Wieringa/Dit zijn de namen/album.nfo");
        assertEquals(FileType.NFO, subject.getFileType());
        assertEquals(DirType.ALBUM, subject.getDirType());
    }

    @Test
    void artistNfoAtAuthorLevel() {
        var subject = new BookPathObject(ROOT, "/books/Tommy Wieringa/artist.nfo");
        assertEquals(FileType.NFO, subject.getFileType());
        assertEquals(DirType.ARTIST, subject.getDirType());
    }

    @Test
    void epubInsideBookDirectoryTakesNameFromDirectory() {
        var subject = new BookPathObject(ROOT, "/books/Owl (1950)/Night Flight (2015)/Night Flight.epub");
        assertEquals(FileType.EPUB, subject.getFileType());
        assertEquals("Night Flight", subject.getBookName());
        assertEquals(2015, subject.getBookYear());
    }

    @Test
    void unknownFileIsIgnored() {
        var subject = new BookPathObject(ROOT, "/books/Tommy Wieringa/Dit zijn de namen/notes.txt");
        assertEquals(FileType.NONE, subject.getFileType());
    }

    @Test
    void pathOutsideRootIsIgnored() {
        var subject = new BookPathObject(ROOT, "/music/Artist/Album/01 - Track.mp3");
        assertEquals(DirType.NONE, subject.getDirType());
        assertEquals(FileType.NONE, subject.getFileType());
    }
}
