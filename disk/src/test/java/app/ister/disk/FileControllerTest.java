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
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        controller = new FileController(imageRepository, mediaFileRepository);
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

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id);

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

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id);

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

        ResponseEntity<InputStreamResource> response = controller.downloadImage(id);

        // Must not throw; content type resolved to a non-null value
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getContentType());
    }

    // ========== downloadTranscode ==========

    @Test
    void downloadTranscodeReturnsStreamForExistingSegment() throws IOException {
        UUID id = UUID.randomUUID();
        Path dir = tempDir.resolve(id.toString());
        Files.createDirectories(dir);
        Path segment = dir.resolve("segment.ts");
        Files.writeString(segment, "video segment data");

        InputStreamResource result = controller.downloadTranscode(id, "segment.ts");

        assertNotNull(result);
        assertEquals(Files.size(segment), result.contentLength());
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
