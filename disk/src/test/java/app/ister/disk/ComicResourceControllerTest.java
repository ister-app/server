package app.ister.disk;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.MediaFileRepository;
import app.ister.disk.events.comicfilefound.CbzParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComicResourceControllerTest {

    private static final int SOURCE_WIDTH = 400;
    private static final int SOURCE_HEIGHT = 600;

    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private PdfPageCache pdfPageCache;

    @TempDir
    Path tempDir;

    private ComicResourceController controller;
    private final UUID mediaFileId = UUID.randomUUID();
    private byte[] pagePng;

    @BeforeEach
    void setUp() throws IOException {
        controller = new ComicResourceController(mediaFileRepository, new CbzParser(), pdfPageCache);

        BufferedImage image = new BufferedImage(SOURCE_WIDTH, SOURCE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        pagePng = png.toByteArray();

        Path cbz = tempDir.resolve("Volume 1.cbz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
            zip.putNextEntry(new ZipEntry("page01.png"));
            zip.write(pagePng);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("page02.png"));
            zip.write("not an image".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        MediaFileEntity mediaFile = mock(MediaFileEntity.class);
        BookEntity book = mock(BookEntity.class);
        lenient().when(mediaFile.getBookEntity()).thenReturn(book);
        lenient().when(mediaFile.getPath()).thenReturn(cbz.toString());
        lenient().when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
    }

    @Test
    void widthDownscalesToJpegWithWidthEtag() throws IOException {
        ResponseEntity<StreamingResponseBody> original = controller.page(mediaFileId, 0, null, null);
        ResponseEntity<StreamingResponseBody> scaled = controller.page(mediaFileId, 0, 200, null);

        assertEquals(HttpStatus.OK, scaled.getStatusCode());
        assertEquals(MediaType.IMAGE_JPEG, scaled.getHeaders().getContentType());
        assertNotEquals(original.getHeaders().getETag(), scaled.getHeaders().getETag());
        assertTrue(scaled.getHeaders().getETag().contains("-w240"));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(body(scaled)));
        assertNotNull(image);
        assertEquals(240, image.getWidth());
        assertEquals(360, image.getHeight());
    }

    @Test
    void widthLargerThanSourceServesTheOriginal() throws IOException {
        ResponseEntity<StreamingResponseBody> response = controller.page(mediaFileId, 0, 480, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(pagePng, body(response));
        assertTrue(response.getHeaders().getETag().contains("-w480"));
    }

    @Test
    void undecodablePageFallsBackToTheOriginalBytes() throws IOException {
        ResponseEntity<StreamingResponseBody> response = controller.page(mediaFileId, 1, 240, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals("not an image".getBytes(StandardCharsets.UTF_8), body(response));
    }

    @Test
    void widthEtagHonorsIfNoneMatch() throws IOException {
        String etag = controller.page(mediaFileId, 0, 240, null).getHeaders().getETag();

        ResponseEntity<StreamingResponseBody> cached = controller.page(mediaFileId, 0, 240, etag);
        assertEquals(HttpStatus.NOT_MODIFIED, cached.getStatusCode());
    }

    @Test
    void withoutWidthTheOriginalIsServedUnchanged() throws IOException {
        ResponseEntity<StreamingResponseBody> response = controller.page(mediaFileId, 0, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(pagePng, body(response));
    }

    private Path pdfMediaFile(UUID id, Integer pageCount) throws IOException {
        Path pdf = tempDir.resolve("Volume 2.pdf");
        Files.write(pdf, "%PDF-1.4 fixture".getBytes(StandardCharsets.UTF_8));
        MediaFileEntity mediaFile = mock(MediaFileEntity.class);
        BookEntity book = mock(BookEntity.class);
        lenient().when(mediaFile.getBookEntity()).thenReturn(book);
        lenient().when(mediaFile.getPath()).thenReturn(pdf.toString());
        lenient().when(mediaFile.getId()).thenReturn(id);
        lenient().when(mediaFile.getPageCount()).thenReturn(pageCount);
        lenient().when(mediaFileRepository.findById(id)).thenReturn(Optional.of(mediaFile));
        return pdf;
    }

    @Test
    void pdfPageServesTheCachedRenderAsJpeg() throws IOException {
        UUID id = UUID.randomUUID();
        Path pdf = pdfMediaFile(id, 3);
        Path rendered = tempDir.resolve("rendered.jpg");
        Files.write(rendered, new byte[]{1, 2, 3});
        when(pdfPageCache.pageJpeg(id, pdf, 1, 1600)).thenReturn(Optional.of(rendered));

        ResponseEntity<StreamingResponseBody> response = controller.page(id, 1, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
        assertArrayEquals(new byte[]{1, 2, 3}, body(response));
        assertTrue(response.getHeaders().getETag().contains("-p1-w1600"));
        assertEquals("private, max-age=31536000, immutable", response.getHeaders().getCacheControl());
    }

    @Test
    void pdfWidthSnapsToABucket() throws IOException {
        UUID id = UUID.randomUUID();
        Path pdf = pdfMediaFile(id, 3);
        Path rendered = tempDir.resolve("rendered.jpg");
        Files.write(rendered, new byte[]{1});
        when(pdfPageCache.pageJpeg(id, pdf, 0, 480)).thenReturn(Optional.of(rendered));

        ResponseEntity<StreamingResponseBody> response = controller.page(id, 0, 300, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getHeaders().getETag().contains("-w480"));
    }

    @Test
    void pdfEtagHonorsIfNoneMatchWithoutRendering() throws IOException {
        UUID id = UUID.randomUUID();
        Path pdf = pdfMediaFile(id, 3);
        Path rendered = tempDir.resolve("rendered.jpg");
        Files.write(rendered, new byte[]{1});
        when(pdfPageCache.pageJpeg(id, pdf, 0, 1600)).thenReturn(Optional.of(rendered));
        String etag = controller.page(id, 0, null, null).getHeaders().getETag();

        ResponseEntity<StreamingResponseBody> cached = controller.page(id, 0, null, etag);

        assertEquals(HttpStatus.NOT_MODIFIED, cached.getStatusCode());
        verify(pdfPageCache, times(1)).pageJpeg(id, pdf, 0, 1600);
    }

    @Test
    void pdfPageOutOfRangeIsNotFound() throws IOException {
        UUID id = UUID.randomUUID();
        pdfMediaFile(id, 3);

        assertEquals(HttpStatus.NOT_FOUND, controller.page(id, 3, null, null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.page(id, -1, null, null).getStatusCode());
    }

    @Test
    void pdfWithoutScannedPageCountIsNotFound() throws IOException {
        UUID id = UUID.randomUUID();
        pdfMediaFile(id, null);

        assertEquals(HttpStatus.NOT_FOUND, controller.page(id, 0, null, null).getStatusCode());
    }

    @Test
    void pdfFailedRenderIsAServerError() throws IOException {
        UUID id = UUID.randomUUID();
        Path pdf = pdfMediaFile(id, 3);
        when(pdfPageCache.pageJpeg(id, pdf, 0, 1600)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, controller.page(id, 0, null, null).getStatusCode());
    }

    private static byte[] body(ResponseEntity<StreamingResponseBody> response) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertNotNull(response.getBody());
        response.getBody().writeTo(out);
        return out.toByteArray();
    }
}
