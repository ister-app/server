package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.NodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageDownloadServiceTest {

    @InjectMocks
    private ImageDownloadService subject;

    @Mock
    private NodeService nodeService;

    @Mock
    private DirectoryRepository directoryRepository;

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

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of(cacheDisk));

        subject.downloadAndSave("http://example.com/img.jpg", ImageType.BACKGROUND, "en", movie, null, null);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageDownload).download(eq("http://example.com/img.jpg"), pathCaptor.capture());

        String downloadedPath = pathCaptor.getValue();
        assertTrue(downloadedPath.startsWith("/cache/"));
        assertTrue(downloadedPath.endsWith(".jpg"));

        verify(imageSave).save(eq(cacheDisk), eq(downloadedPath), eq(ImageType.BACKGROUND), eq("en"),
                eq("TMDB://http://example.com/img.jpg"), eq(movie), eq(null), eq(null));
    }

    @Test
    void downloadAndSaveThrowsWhenNoCacheDirectory() {
        NodeEntity node = buildNode();
        ShowEntity show = ShowEntity.builder().id(UUID.randomUUID()).build();

        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, () ->
                subject.downloadAndSave("http://example.com/img.jpg", ImageType.COVER, "nl", null, show, null));
    }
}
