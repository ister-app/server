package app.ister.disk.events.updateimagesrequested;

import app.ister.core.entity.ImageEntity;
import app.ister.core.repository.ImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlurHashChunkProcessorTest {

    private static final UUID DIRECTORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @InjectMocks
    private BlurHashChunkProcessor subject;

    @Mock
    private ImageRepository imageRepository;

    @Test
    void processWithoutCursorStartsAtTheBeginningOfTheDirectory() {
        when(imageRepository.findByDirectoryEntityIdAndBlurHashIsNullOrderById(eq(DIRECTORY_ID), any()))
                .thenReturn(List.of());

        BlurHashChunkProcessor.Chunk chunk = subject.process(DIRECTORY_ID, null, 500);

        assertTrue(chunk.isEmpty());
        assertNull(chunk.lastId());
        verify(imageRepository, never()).saveAll(any());
    }

    @Test
    void processWithCursorResumesAfterIt() {
        UUID cursor = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(imageRepository.findByDirectoryEntityIdAndBlurHashIsNullAndIdGreaterThanOrderById(
                DIRECTORY_ID, cursor, Limit.of(500))).thenReturn(List.of());

        assertTrue(subject.process(DIRECTORY_ID, cursor, 500).isEmpty());

        verify(imageRepository, never()).findByDirectoryEntityIdAndBlurHashIsNullOrderById(any(), any());
    }

    @Test
    void processComputesBlurHashAndReportsLastIdAsNextCursor(@TempDir Path tempDir) throws IOException {
        ImageEntity image = imageAt(tempDir.resolve("cover.png"));
        when(imageRepository.findByDirectoryEntityIdAndBlurHashIsNullOrderById(eq(DIRECTORY_ID), any()))
                .thenReturn(List.of(image));

        BlurHashChunkProcessor.Chunk chunk = subject.process(DIRECTORY_ID, null, 500);

        assertEquals(1, chunk.size());
        assertEquals(image.getId(), chunk.lastId());
        assertNotNull(image.getBlurHash());
        assertNotNull(image.getFileLastModifiedTime());
        assertNotNull(image.getFileCreationTime());
        verify(imageRepository).saveAll(List.of(image));
    }

    /**
     * The sweep must terminate even when an image can never be hashed (a CMYK JPEG, a corrupt file).
     * Such an image keeps a null blur-hash, so the cursor -- not the blur-hash -- is what moves the
     * sweep forward past it.
     */
    @Test
    void processAdvancesCursorPastAnImageThatCannotBeHashed(@TempDir Path tempDir) throws IOException {
        ImageEntity readable = imageAt(tempDir.resolve("cover.png"));
        ImageEntity unreadable = ImageEntity.builder()
                .id(UUID.fromString("33333333-3333-3333-3333-333333333333"))
                .path(tempDir.resolve("does-not-exist.jpg").toString())
                .build();
        when(imageRepository.findByDirectoryEntityIdAndBlurHashIsNullOrderById(eq(DIRECTORY_ID), any()))
                .thenReturn(List.of(readable, unreadable));

        BlurHashChunkProcessor.Chunk chunk = subject.process(DIRECTORY_ID, null, 500);

        assertEquals(2, chunk.size());
        assertEquals(unreadable.getId(), chunk.lastId(), "cursor must move past the unhashable image");
        assertNull(unreadable.getBlurHash());
        assertNotNull(readable.getBlurHash(), "one bad image must not fail the rest of the chunk");
        verify(imageRepository).saveAll(List.of(readable, unreadable));
    }

    private static ImageEntity imageAt(Path path) throws IOException {
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        return ImageEntity.builder().id(UUID.randomUUID()).path(path.toString()).build();
    }
}
