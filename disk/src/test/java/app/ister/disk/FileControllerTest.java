package app.ister.disk;

import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock private ImageRepository imageRepository;
    @Mock private MediaFileRepository mediaFileRepository;

    @TempDir Path tempDir;

    private FileController controller;

    @BeforeEach
    void setUp() {
        controller = new FileController(imageRepository, mediaFileRepository,
                new ImageThumbnailCache(new ImageScaler(), tempDir.resolve("tmp").toString()));
        ReflectionTestUtils.setField(controller, "tmpDir", tempDir.toString());
    }

    // ========== downloadImage ==========

    @Test
    void downloadImageReturns200WithBodyWhenFileExists() throws IOException {
        UUID id = UUID.randomUUID();
        Path imageFile = tempDir.resolve("cover.jpg");
        Files.writeString(imageFile, "fake image data");

        ImageEntity imageEntity = mock(ImageEntity.class);
        when(imageEntity.getPath()).thenReturn(imageFile.toString());
        when(imageRepository.findById(id)).thenReturn(Optional.of(imageEntity));

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(Files.size(imageFile), response.getBody().contentLength());
    }

    @Test
    void downloadImageReturns404WhenFileDoesNotExist() throws IOException {
        UUID id = UUID.randomUUID();

        ImageEntity imageEntity = mock(ImageEntity.class);
        when(imageEntity.getPath()).thenReturn(tempDir.resolve("nonexistent.jpg").toString());
        when(imageRepository.findById(id)).thenReturn(Optional.of(imageEntity));

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, null, null);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void downloadImageFallsBackToOctetStreamWhenContentTypeIsUnknown() throws IOException {
        UUID id = UUID.randomUUID();
        // File with no recognisable extension → probeContentType may return null
        Path imageFile = tempDir.resolve("cover.unknownext");
        Files.writeString(imageFile, "image bytes");

        ImageEntity imageEntity = mock(ImageEntity.class);
        when(imageEntity.getPath()).thenReturn(imageFile.toString());
        when(imageRepository.findById(id)).thenReturn(Optional.of(imageEntity));

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, null, null);

        // Must not throw; content type resolved to a non-null value
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getContentType());
    }

    @Test
    void downloadImageIsCacheableAndCarriesAnETag() throws IOException {
        UUID id = UUID.randomUUID();
        Path imageFile = tempDir.resolve("cover.jpg");
        Files.writeString(imageFile, "fake image data");

        ImageEntity imageEntity = mock(ImageEntity.class);
        when(imageEntity.getPath()).thenReturn(imageFile.toString());
        when(imageRepository.findById(id)).thenReturn(Optional.of(imageEntity));

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, null, null);

        assertNotNull(response.getHeaders().getETag());
        assertEquals("private, max-age=86400", response.getHeaders().getCacheControl());
    }

    @Test
    void downloadImageReturns304ForAMatchingETag() throws IOException {
        UUID id = UUID.randomUUID();
        Path imageFile = tempDir.resolve("cover.jpg");
        Files.writeString(imageFile, "fake image data");

        ImageEntity imageEntity = mock(ImageEntity.class);
        when(imageEntity.getPath()).thenReturn(imageFile.toString());
        when(imageRepository.findById(id)).thenReturn(Optional.of(imageEntity));

        String etag = controller.downloadImage(id, null, null).getHeaders().getETag();
        ResponseEntity<InputStreamResource> notModified = controller.downloadImage(id, null, etag);

        assertEquals(304, notModified.getStatusCode().value());
        assertNull(notModified.getBody());
        assertEquals(etag, notModified.getHeaders().getETag());
    }

    @Test
    void downloadImageETagChangesWhenTheFileIsReplacedInPlace() throws IOException {
        UUID id = UUID.randomUUID();
        Path imageFile = tempDir.resolve("cover.jpg");
        Files.writeString(imageFile, "fake image data");

        ImageEntity imageEntity = mock(ImageEntity.class);
        when(imageEntity.getPath()).thenReturn(imageFile.toString());
        when(imageRepository.findById(id)).thenReturn(Optional.of(imageEntity));

        String before = controller.downloadImage(id, null, null).getHeaders().getETag();

        // A rescan reuses the row for a given directory and path, so the id survives a replaced
        // file — the ETag is what tells a client its copy is stale.
        Files.writeString(imageFile, "different image data");
        Files.setLastModifiedTime(imageFile, FileTime.fromMillis(1_700_000_000_000L));

        ResponseEntity<InputStreamResource> after = controller.downloadImage(id, null, before);

        assertEquals(200, after.getStatusCode().value());
        assertNotEquals(before, after.getHeaders().getETag());
    }

    // ========== downloadImage?width= ==========

    /** A real jpeg/png on disk, wide enough that every bucket is a genuine downscale. */
    private Path writeImage(String name, int width, int height, boolean withAlpha) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                withAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, (withAlpha ? 0x80000000 : 0xFF000000) | (x * 7 + y * 3) % 0xFFFFFF);
            }
        }
        Path file = tempDir.resolve(name);
        ImageIO.write(image, withAlpha ? "png" : "jpg", file.toFile());
        return file;
    }

    private UUID stubImage(Path file) {
        UUID id = UUID.randomUUID();
        ImageEntity imageEntity = mock(ImageEntity.class);
        when(imageEntity.getPath()).thenReturn(file.toString());
        when(imageRepository.findById(id)).thenReturn(Optional.of(imageEntity));
        return id;
    }

    private static byte[] body(ResponseEntity<InputStreamResource> response) throws IOException {
        return response.getBody().getInputStream().readAllBytes();
    }

    @Test
    void widthDownscalesToJpegWithAWidthEtag() throws IOException {
        UUID id = stubImage(writeImage("cover.jpg", 1000, 1500, false));

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, 300, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getETag().contains("-w320"), response.getHeaders().getETag());
        BufferedImage served = ImageIO.read(new ByteArrayInputStream(body(response)));
        assertEquals(320, served.getWidth());
        assertEquals(480, served.getHeight());
    }

    @Test
    void widthKeepsTheAlphaChannelAsPng() throws IOException {
        UUID id = stubImage(writeImage("logo.png", 800, 800, true));

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, 160, null);

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        BufferedImage served = ImageIO.read(new ByteArrayInputStream(body(response)));
        assertEquals(160, served.getWidth());
        assertTrue(served.getColorModel().hasAlpha());
    }

    @Test
    void aSecondRequestIsServedFromTheDiskCache() throws IOException {
        UUID id = stubImage(writeImage("cover.jpg", 1000, 1500, false));

        byte[] first = body(controller.downloadImage(id, 320, null));
        byte[] second = body(controller.downloadImage(id, 320, null));

        assertArrayEquals(first, second);
        try (var files = Files.walk(tempDir.resolve("tmp").resolve("image-thumbs"))) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void aWidthAboveTheTopBucketServesTheOriginal() throws IOException {
        Path file = writeImage("cover.jpg", 1000, 1500, false);
        UUID id = stubImage(file);

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, 4000, null);

        assertArrayEquals(Files.readAllBytes(file), body(response));
        assertFalse(response.getHeaders().getETag().contains("-w"));
    }

    @Test
    void aSourceNarrowerThanTheBucketServesTheOriginal() throws IOException {
        Path file = writeImage("thumb.jpg", 100, 150, false);
        UUID id = stubImage(file);

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, 320, null);

        assertArrayEquals(Files.readAllBytes(file), body(response));
        // The ETag still carries the requested bucket, so revalidation stays per-variant.
        assertTrue(response.getHeaders().getETag().contains("-w320"));
    }

    @Test
    void anUndecodableSourceServesTheOriginal() throws IOException {
        Path file = tempDir.resolve("broken.jpg");
        Files.writeString(file, "not an image at all");
        UUID id = stubImage(file);

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, 320, null);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(Files.readAllBytes(file), body(response));
    }

    @Test
    void theWidthEtagHonoursIfNoneMatchWithoutScaling() throws IOException {
        UUID id = stubImage(writeImage("cover.jpg", 1000, 1500, false));

        String etag = controller.downloadImage(id, 320, null).getHeaders().getETag();
        Path thumbnails = tempDir.resolve("tmp").resolve("image-thumbs");
        try (var files = Files.walk(thumbnails)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                try {
                    Files.delete(f);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }

        ResponseEntity<InputStreamResource> cached = controller.downloadImage(id, 320, etag);

        assertEquals(304, cached.getStatusCode().value());
        assertNull(cached.getBody());
        try (var files = Files.walk(thumbnails)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void aWidthEtagDoesNotMatchTheOriginalRequest() throws IOException {
        UUID id = stubImage(writeImage("cover.jpg", 1000, 1500, false));

        String sizedEtag = controller.downloadImage(id, 320, null).getHeaders().getETag();
        ResponseEntity<InputStreamResource> original = controller.downloadImage(id, null, sizedEtag);

        assertEquals(200, original.getStatusCode().value());
    }

    // ========== downloadMediaFile ==========

    @Test
    void downloadMediaFileReturnsStreamForExistingFile() throws IOException {
        UUID id = UUID.randomUUID();
        Path mediaFile = tempDir.resolve("video.mkv");
        Files.writeString(mediaFile, "video file data");

        MediaFileEntity entity = mock(MediaFileEntity.class);
        when(entity.getPath()).thenReturn(mediaFile.toString());
        when(mediaFileRepository.findById(id)).thenReturn(Optional.of(entity));

        InputStreamResource result = controller.downloadMediaFile(id);

        assertNotNull(result);
        assertEquals(Files.size(mediaFile), result.contentLength());
    }

    // ========== uploadTranscode ==========

    @Test
    void uploadTranscodeStoresFileAndReturns200() throws IOException {
        UUID id = UUID.randomUUID();
        byte[] data = "transcoded video data".getBytes();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(data);

        ResponseEntity<Void> response = controller.uploadTranscode(id, "output.ts", request);

        assertEquals(200, response.getStatusCode().value());
        Path stored = tempDir.resolve(id.toString()).resolve("output.ts");
        assertTrue(Files.exists(stored));
        assertArrayEquals(data, Files.readAllBytes(stored));
    }

    @Test
    void uploadTranscodeCreatesParentDirectoryIfAbsent() throws IOException {
        UUID id = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("data".getBytes());

        Path expectedDir = tempDir.resolve(id.toString());
        assertFalse(Files.exists(expectedDir));

        controller.uploadTranscode(id, "chunk.ts", request);

        assertTrue(Files.isDirectory(expectedDir));
    }
}
