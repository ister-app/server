package app.ister.disk;

import app.ister.disk.events.comicfilefound.PdfParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PdfPageCacheTest {

    @Mock
    private PdfParser pdfParser;

    @TempDir
    Path tmpDir;

    private PdfPageCache cache;
    private final UUID mediaFileId = UUID.randomUUID();
    private final Path pdf = Path.of("/library/volume.pdf");

    @BeforeEach
    void setUp() {
        cache = new PdfPageCache(pdfParser, tmpDir.toString());
    }

    @Test
    void missRendersAndWritesTheCacheFile() throws IOException {
        when(pdfParser.renderPageJpeg(pdf, 0, 1600)).thenReturn(Optional.of(new byte[]{1, 2}));

        Optional<Path> page = cache.pageJpeg(mediaFileId, pdf, 0, 1600);

        assertTrue(page.isPresent());
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(page.get()));
        assertTrue(page.get().startsWith(tmpDir.resolve(mediaFileId.toString())));
    }

    @Test
    void hitServesWithoutRerendering() throws IOException {
        when(pdfParser.renderPageJpeg(pdf, 0, 1600)).thenReturn(Optional.of(new byte[]{1}));

        cache.pageJpeg(mediaFileId, pdf, 0, 1600);
        Optional<Path> again = cache.pageJpeg(mediaFileId, pdf, 0, 1600);

        assertTrue(again.isPresent());
        verify(pdfParser, times(1)).renderPageJpeg(any(), anyInt(), anyInt());
    }

    @Test
    void failedRenderCachesNothing() throws IOException {
        when(pdfParser.renderPageJpeg(pdf, 0, 1600)).thenReturn(Optional.empty());

        assertTrue(cache.pageJpeg(mediaFileId, pdf, 0, 1600).isEmpty());
        assertTrue(Files.notExists(tmpDir.resolve(mediaFileId.toString())
                .resolve("comic-page-0-w1600.jpg")));
    }
}
