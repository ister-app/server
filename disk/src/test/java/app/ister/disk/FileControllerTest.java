package app.ister.disk;

import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

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

    @InjectMocks
    private FileController controller;

    @BeforeEach
    void setUp() {
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

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, null);

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

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, null);

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

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, null);

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

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id, null);

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

        String etag = controller.downloadImage(id, null).getHeaders().getETag();
        ResponseEntity<InputStreamResource> notModified = controller.downloadImage(id, etag);

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

        String before = controller.downloadImage(id, null).getHeaders().getETag();

        // A rescan reuses the row for (directory, path), so the id survives a replaced file —
        // the ETag is what tells a client its copy is stale.
        Files.writeString(imageFile, "different image data");
        Files.setLastModifiedTime(imageFile, FileTime.fromMillis(1_700_000_000_000L));

        ResponseEntity<InputStreamResource> after = controller.downloadImage(id, before);

        assertEquals(200, after.getStatusCode().value());
        assertNotEquals(before, after.getHeaders().getETag());
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
