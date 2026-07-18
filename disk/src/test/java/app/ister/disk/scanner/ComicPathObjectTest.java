package app.ister.disk.scanner;

import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComicPathObjectTest {

    private static final String ROOT = "/comics";

    private ComicPathObject parse(String relative) {
        return new ComicPathObject(ROOT, ROOT + "/" + relative);
    }

    @Test
    void seriesDirectoryWithStartYear() {
        ComicPathObject path = parse("Rick and Morty (2023)");

        assertEquals(DirType.SERIES, path.getDirType());
        assertEquals("Rick and Morty", path.getSeriesName());
        assertEquals(2023, path.getStartYear());
        assertEquals(FileType.NONE, path.getFileType());
    }

    @Test
    void seriesDirectoryWithDotsInNameIsNotMistakenForAFile() {
        ComicPathObject path = new ComicPathObject(ROOT, ROOT + "/Dr. Stone", true);

        assertEquals(DirType.SERIES, path.getDirType());
        assertEquals("Dr. Stone", path.getSeriesName());
        assertEquals(FileType.NONE, path.getFileType());
    }

    @Test
    void volumeInsideASeriesWithDotsInName() {
        ComicPathObject path = parse("Dr. Stone/Volume 3.cbz");

        assertEquals(DirType.SERIES, path.getDirType());
        assertEquals("Dr. Stone", path.getSeriesName());
        assertEquals(FileType.COMIC, path.getFileType());
    }

    @Test
    void seriesDirectoryWithoutYear() {
        ComicPathObject path = parse("Attack on Titan");

        assertEquals(DirType.SERIES, path.getDirType());
        assertEquals("Attack on Titan", path.getSeriesName());
        assertEquals(0, path.getStartYear());
    }

    @Test
    void pdfVolumeInsideASeries() {
        ComicPathObject path = parse("Attack on Titan (2009)/attackontitan_vol27.pdf");

        assertEquals(FileType.COMIC, path.getFileType());
        assertEquals("Attack on Titan", path.getSeriesName());
        assertEquals(2009, path.getStartYear());
        assertEquals("attackontitan_vol27", path.getVolumeName());
        assertEquals(27.0, path.getVolumeNumber());
        assertEquals("Volume 27", path.getVolumeTitle());
        assertEquals("pdf", path.getExtension());
    }

    /** pdf, cbz and epub of one volume share the identity name → one volume row. */
    @Test
    void formatsOfOneVolumeShareTheIdentityName() {
        ComicPathObject pdf = parse("Fairy Tail/fairytail_vol12.pdf");
        ComicPathObject cbz = parse("Fairy Tail/fairytail_vol12.cbz");

        assertEquals(pdf.getVolumeName(), cbz.getVolumeName());
        assertEquals(FileType.COMIC, cbz.getFileType());
        assertEquals("cbz", cbz.getExtension());
    }

    @Test
    void seriesArtworkIsAnImage() {
        assertEquals(FileType.IMAGE, parse("Fairy Tail/cover.jpg").getFileType());
        assertEquals(FileType.IMAGE, parse("Fairy Tail/folder.png").getFileType());
    }

    @Test
    void unknownExtensionsAreIgnored() {
        assertEquals(FileType.NONE, parse("Fairy Tail/notes.txt").getFileType());
    }

    @Test
    void looseFileUnderTheRootIsIgnored() {
        ComicPathObject path = parse("stray_vol1.pdf");

        assertEquals(DirType.NONE, path.getDirType());
        assertEquals(FileType.NONE, path.getFileType());
        assertNull(path.getSeriesName());
    }

    @Test
    void deeperNestingIsIgnored() {
        ComicPathObject path = parse("Fairy Tail/extras/bonus_vol1.cbz");

        assertEquals(DirType.NONE, path.getDirType());
        assertEquals(FileType.NONE, path.getFileType());
    }

    @Test
    void pathOutsideTheRootIsIgnored() {
        ComicPathObject path = new ComicPathObject(ROOT, "/elsewhere/Series/vol1.cbz");

        assertEquals(DirType.NONE, path.getDirType());
    }
}
