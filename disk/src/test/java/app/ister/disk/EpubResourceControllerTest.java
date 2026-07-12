package app.ister.disk;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.MediaFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpubResourceControllerTest {

    private static final byte[] CHAPTER = "<html>chapter one</html>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUDIO = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Mock
    private MediaFileRepository mediaFileRepository;

    @TempDir
    Path tempDir;

    private EpubResourceController controller;
    private final UUID mediaFileId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        controller = new EpubResourceController(mediaFileRepository);

        Path epub = tempDir.resolve("book.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub))) {
            zip.putNextEntry(new ZipEntry("OEBPS/chapter_001.xhtml"));
            zip.write(CHAPTER);
            zip.closeEntry();
            ZipEntry stored = new ZipEntry("OEBPS/audio/chapter_001.mp3");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(AUDIO.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(AUDIO);
            stored.setCrc(crc.getValue());
            zip.putNextEntry(stored);
            zip.write(AUDIO);
            zip.closeEntry();
        }

        MediaFileEntity mediaFile = mock(MediaFileEntity.class);
        lenient().when(mediaFile.getBookEntity()).thenReturn(mock(BookEntity.class));
        lenient().when(mediaFile.getPath()).thenReturn(epub.toString());
        lenient().when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
    }

    private byte[] bodyOf(ResponseEntity<StreamingResponseBody> response) throws IOException {
        assertNotNull(response.getBody());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        return output.toByteArray();
    }

    @Test
    void servesEntryWithContentTypeAndCaching() throws IOException {
        var response = controller.resource(mediaFileId, "/OEBPS/chapter_001.xhtml", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/xhtml+xml", String.valueOf(response.getHeaders().getContentType()));
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertNotNull(response.getHeaders().getETag());
        assertEquals("private, max-age=31536000, immutable", response.getHeaders().getCacheControl());
        assertArrayEquals(CHAPTER, bodyOf(response));
    }

    @Test
    void etagMatchReturns304() throws IOException {
        var first = controller.resource(mediaFileId, "/OEBPS/chapter_001.xhtml", null, null);
        var second = controller.resource(mediaFileId, "/OEBPS/chapter_001.xhtml", null, first.getHeaders().getETag());

        assertEquals(304, second.getStatusCode().value());
        assertNull(second.getBody());
    }

    @Test
    void servesByteRangeFromStoredAudio() throws IOException {
        var response = controller.resource(mediaFileId, "/OEBPS/audio/chapter_001.mp3", "bytes=4-7", null);

        assertEquals(206, response.getStatusCode().value());
        assertEquals("bytes 4-7/16", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(4, response.getHeaders().getContentLength());
        assertArrayEquals("4567".getBytes(StandardCharsets.UTF_8), bodyOf(response));
    }

    @Test
    void servesOpenEndedRange() throws IOException {
        var response = controller.resource(mediaFileId, "/OEBPS/audio/chapter_001.mp3", "bytes=10-", null);

        assertEquals(206, response.getStatusCode().value());
        assertArrayEquals("abcdef".getBytes(StandardCharsets.UTF_8), bodyOf(response));
    }

    @Test
    void invalidRangeFallsBackToFullResponse() throws IOException {
        var response = controller.resource(mediaFileId, "/OEBPS/audio/chapter_001.mp3", "bytes=99-100", null);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(AUDIO, bodyOf(response));
    }

    @Test
    void rejectsPathTraversal() throws IOException {
        var response = controller.resource(mediaFileId, "/../secrets.txt", null, null);
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void unknownEntryIs404() throws IOException {
        var response = controller.resource(mediaFileId, "/OEBPS/missing.xhtml", null, null);
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void mediaFileWithoutBookIs404() throws IOException {
        UUID otherId = UUID.randomUUID();
        MediaFileEntity notABook = mock(MediaFileEntity.class);
        when(notABook.getBookEntity()).thenReturn(null);
        when(mediaFileRepository.findById(otherId)).thenReturn(Optional.of(notABook));

        var response = controller.resource(otherId, "/OEBPS/chapter_001.xhtml", null, null);
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void unknownMediaFileIs404() throws IOException {
        when(mediaFileRepository.findById(mediaFileId)).thenReturn(Optional.empty());
        var response = controller.resource(mediaFileId, "/OEBPS/chapter_001.xhtml", null, null);
        assertEquals(404, response.getStatusCode().value());
    }
}
