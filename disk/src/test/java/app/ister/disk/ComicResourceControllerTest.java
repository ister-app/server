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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComicResourceControllerTest {

    private static final int SOURCE_WIDTH = 400;
    private static final int SOURCE_HEIGHT = 600;

    @Mock
    private MediaFileRepository mediaFileRepository;

    @TempDir
    Path tempDir;

    private ComicResourceController controller;
    private final UUID mediaFileId = UUID.randomUUID();
    private byte[] pagePng;

    @BeforeEach
    void setUp() throws IOException {
        controller = new ComicResourceController(mediaFileRepository, new CbzParser());

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
        lenient().when(mediaFile.getBookEntity()).thenReturn(mock(BookEntity.class));
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

    private static byte[] body(ResponseEntity<StreamingResponseBody> response) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertNotNull(response.getBody());
        response.getBody().writeTo(out);
        return out.toByteArray();
    }
}
