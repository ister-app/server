package app.ister.api.controller;

import app.ister.core.entity.MediaFileEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaFileControllerTest {

    private final MediaFileController subject = new MediaFileController();

    @Test
    void formatIsTheUppercasedExtension() {
        assertEquals("CBZ", format("/comics/Naruto (1999)/Volume 1.cbz"));
        assertEquals("PDF", format("/comics/Asterix (1961)/Vol 3.PDF"));
        assertEquals("EPUB", format("/books/author/book.epub"));
        assertEquals("MKV", format("/shows/show/S01E01.mkv"));
    }

    @Test
    void formatIsNullWithoutAnExtension() {
        assertNull(format("/comics/series/volume"));
        assertNull(format("/comics/series/volume."));
        assertNull(format("/comics/ser.ies (1999)/volume"));
    }

    private String format(String path) {
        MediaFileEntity mediaFile = mock(MediaFileEntity.class);
        when(mediaFile.getPath()).thenReturn(path);
        return subject.format(mediaFile);
    }
}
