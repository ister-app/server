package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.ImageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageDownloadServiceTest {

    @InjectMocks
    private ImageDownloadService subject;

    @Mock
    private app.ister.core.storage.CacheDirectoryResolver cacheDirectoryResolver;

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    @Mock
    private ImageDownload imageDownload;

    @Mock
    private ImageSave imageSave;

    private NodeEntity buildNode() {
        return NodeEntity.builder().name("node1").url("http://localhost").build();
    }

    private DirectoryEntity buildCacheDisk(NodeEntity node) {
        return DirectoryEntity.builder()
                .id(UUID.randomUUID())
                .nodeEntity(node)
                .path("/cache/")
                .name("cache")
                .build();
    }

    @Test
    void downloadAndSaveCallsDownloadAndSave() throws IOException {
        NodeEntity node = buildNode();
        DirectoryEntity cacheDisk = buildCacheDisk(node);
        MovieEntity movie = MovieEntity.builder().id(UUID.randomUUID()).build();

        cacheDisk.setPath(tempDir.resolve("cache").toString());
        when(cacheDirectoryResolver.store()).thenReturn(new app.ister.core.storage.LocalCacheStore(cacheDisk));
        org.springframework.test.util.ReflectionTestUtils.setField(subject, "tmpDir", tempDir.resolve("tmp").toString());

        subject.downloadAndSave("http://example.com/img.jpg", ImageType.BACKGROUND, "en",
                "TMDB://http://example.com/img.jpg", new ImageSave.MediaEntityRef(movie, null, null, null, null));

        // downloaded into tmp, then moved into the cache directory under a fresh name
        ArgumentCaptor<String> downloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageDownload).download(eq("http://example.com/img.jpg"), downloadCaptor.capture());
        assertTrue(downloadCaptor.getValue().startsWith(tempDir.resolve("tmp").toString()));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageSave).save(eq(cacheDisk), pathCaptor.capture(), eq(ImageType.BACKGROUND), eq("en"),
                eq("TMDB://http://example.com/img.jpg"), eq(new ImageSave.MediaEntityRef(movie, null, null, null, null)));
        String storedPath = pathCaptor.getValue();
        assertTrue(storedPath.startsWith(tempDir.resolve("cache").toString()));
        assertTrue(storedPath.endsWith(".jpg"));
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(storedPath)));
        try (var tmpFiles = java.nio.file.Files.list(tempDir.resolve("tmp"))) {
            assertEquals(0, tmpFiles.count(), "no download left in tmp");
        }
    }

    @Test
    void downloadAndSaveThrowsWhenNoCacheDirectory() {
        when(cacheDirectoryResolver.store()).thenThrow(new IllegalStateException("This node has no cache directory"));
        org.springframework.test.util.ReflectionTestUtils.setField(subject, "tmpDir", tempDir.resolve("tmp").toString());

        ImageSave.MediaEntityRef ref = new ImageSave.MediaEntityRef(null, null, null, null, null);
        assertThrows(IllegalStateException.class, () ->
                subject.downloadAndSave("http://example.com/img.jpg", ImageType.COVER, "nl",
                        "TMDB://http://example.com/img.jpg", ref));
    }
}
