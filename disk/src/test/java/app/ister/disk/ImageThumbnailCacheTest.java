package app.ister.disk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ImageThumbnailCacheTest {

    @TempDir Path tempDir;

    private ImageThumbnailCache cache;
    private Path tmpDir;

    @BeforeEach
    void setUp() {
        tmpDir = tempDir.resolve("tmp");
        cache = new ImageThumbnailCache(new ImageScaler(), tmpDir.toString());
    }

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

    private List<Path> cachedFiles() throws IOException {
        if (!Files.isDirectory(tmpDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(tmpDir)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void aMissGeneratesAJpegUnderTheShardedThumbnailDirectory() throws IOException {
        UUID id = UUID.randomUUID();
        Path source = writeImage("cover.jpg", 800, 1200, false);

        Optional<ImageThumbnailCache.Thumbnail> thumbnail = cache.thumbnail(id, source, 320);

        assertTrue(thumbnail.isPresent());
        assertEquals(MediaType.IMAGE_JPEG, thumbnail.get().contentType());
        Path relative = tmpDir.relativize(thumbnail.get().path());
        assertEquals(ImageThumbnailCache.THUMBNAIL_DIR, relative.getName(0).toString());
        assertEquals(id.toString().substring(0, 2), relative.getName(1).toString());
        assertTrue(relative.getFileName().toString().endsWith("-w320.jpg"), relative.toString());
        assertEquals(320, ImageIO.read(thumbnail.get().path().toFile()).getWidth());
    }

    @Test
    void aTransparentSourceIsCachedAsPng() throws IOException {
        Optional<ImageThumbnailCache.Thumbnail> thumbnail =
                cache.thumbnail(UUID.randomUUID(), writeImage("logo.png", 800, 800, true), 240);

        assertTrue(thumbnail.isPresent());
        assertEquals(MediaType.IMAGE_PNG, thumbnail.get().contentType());
        assertTrue(thumbnail.get().path().getFileName().toString().endsWith("-w240.png"));
    }

    @Test
    void aHitTouchesTheFileAndDoesNotRegenerate() throws IOException {
        UUID id = UUID.randomUUID();
        Path source = writeImage("cover.jpg", 800, 1200, false);
        Path cached = cache.thumbnail(id, source, 320).orElseThrow().path();
        Files.setLastModifiedTime(cached, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        byte[] before = Files.readAllBytes(cached);

        Path again = cache.thumbnail(id, source, 320).orElseThrow().path();

        assertEquals(cached, again);
        assertArrayEquals(before, Files.readAllBytes(again));
        assertTrue(Files.getLastModifiedTime(again).toMillis() > System.currentTimeMillis() - 10_000);
        assertEquals(1, cachedFiles().size());
    }

    @Test
    void concurrentCallersGenerateOnce() throws Exception {
        UUID id = UUID.randomUUID();
        Path source = writeImage("cover.jpg", 1600, 2400, false);
        List<Callable<Path>> calls = java.util.Collections.nCopies(8,
                () -> cache.thumbnail(id, source, 640).orElseThrow().path());

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (var future : pool.invokeAll(calls)) {
                assertNotNull(future.get());
            }
        }

        assertEquals(1, cachedFiles().size());
    }

    @Test
    void replacingTheSourceInPlaceProducesADifferentPath() throws IOException {
        UUID id = UUID.randomUUID();
        Path source = writeImage("cover.jpg", 800, 1200, false);
        Path first = cache.thumbnail(id, source, 320).orElseThrow().path();

        Files.delete(source);
        writeImage("cover.jpg", 900, 1350, false);
        Path second = cache.thumbnail(id, source, 320).orElseThrow().path();

        assertNotEquals(first, second);
        assertEquals(2, cachedFiles().size());
    }

    @Test
    void aSourceNarrowerThanTheWidthIsNotCached() throws IOException {
        Optional<ImageThumbnailCache.Thumbnail> thumbnail =
                cache.thumbnail(UUID.randomUUID(), writeImage("small.jpg", 100, 150, false), 320);

        assertTrue(thumbnail.isEmpty());
        assertEquals(List.of(), cachedFiles());
    }
}
