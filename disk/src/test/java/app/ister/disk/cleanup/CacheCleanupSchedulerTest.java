package app.ister.disk.cleanup;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.NodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheCleanupSchedulerTest {

    @Mock
    private NodeService nodeService;
    @Mock
    private DirectoryRepository directoryRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private MediaFileStreamRepository mediaFileStreamRepository;
    @Mock
    private WatchStatusRepository watchStatusRepository;
    @Mock
    private CacheCleanupService cacheCleanupService;

    @InjectMocks
    private CacheCleanupScheduler subject;

    @TempDir
    Path cachePath;

    private NodeEntity node;
    private DirectoryEntity cacheDir;
    private final UUID cacheDirId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        node = NodeEntity.builder().name("node1").build();
        cacheDir = DirectoryEntity.builder()
                .id(cacheDirId)
                .nodeEntity(node)
                .name("node1-cache-directory")
                .path(cachePath.toString())
                .directoryType(DirectoryType.CACHE)
                .build();

        ReflectionTestUtils.setField(subject, "enabled", true);
        ReflectionTestUtils.setField(subject, "dryRun", false);
        ReflectionTestUtils.setField(subject, "minAge", Duration.ofHours(24));
        ReflectionTestUtils.setField(subject, "podcastRetentionDays", 30L);
    }

    private void withCacheDirectory() {
        lenient().when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        lenient().when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node))
                .thenReturn(List.of(cacheDir));
    }

    private MediaFileEntity download(Path path, Instant created) {
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder().guid("guid").build();
        episode.setId(UUID.randomUUID());
        MediaFileEntity download = MediaFileEntity.builder()
                .path(path.toString())
                .podcastEpisodeEntity(episode)
                .size(10L)
                .build();
        download.setId(UUID.randomUUID());
        download.setDateCreated(created);
        return download;
    }

    @Test
    void disabledDoesNothing() {
        ReflectionTestUtils.setField(subject, "enabled", false);

        subject.run();

        verifyNoInteractions(nodeService, directoryRepository, cacheCleanupService);
    }

    @Test
    void noCacheDirectoryDoesNothing() throws IOException {
        when(nodeService.getOrCreateNodeEntityForThisNode()).thenReturn(node);
        when(directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node)).thenReturn(List.of());

        subject.run();

        verify(cacheCleanupService, never()).clean(any(), any(), any(), anyBoolean());
    }

    @Test
    void cleansWithReferencedImageMediaAndStreamPaths() throws IOException {
        withCacheDirectory();
        when(imageRepository.findPathsByDirectoryEntityId(cacheDirId))
                .thenReturn(List.of(cachePath + "/image.jpg"));
        when(mediaFileRepository.findPathsByDirectoryEntityId(cacheDirId))
                .thenReturn(List.of(cachePath + "/podcasts/episode.mp3"));
        when(mediaFileStreamRepository.findAllNonNullPaths())
                .thenReturn(List.of(cachePath + "/subtitle.vtt", "/other-disk/subtitle.vtt"));
        when(cacheCleanupService.clean(any(), any(), any(), anyBoolean()))
                .thenReturn(new CacheCleanupService.CleanupResult(1, 1024, 2));

        subject.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> referenced = ArgumentCaptor.forClass(Set.class);
        verify(cacheCleanupService).clean(eq(cachePath), referenced.capture(), eq(Duration.ofHours(24)), eq(false));
        assertEquals(Set.of(cachePath + "/image.jpg",
                        cachePath + "/podcasts/episode.mp3",
                        cachePath + "/subtitle.vtt"),
                referenced.getValue());
    }

    @Test
    void cleanFailureIsSwallowed() throws IOException {
        withCacheDirectory();
        when(cacheCleanupService.clean(any(), any(), any(), anyBoolean())).thenThrow(new IOException("boom"));

        subject.run();

        verify(cacheCleanupService).clean(any(), any(), any(), anyBoolean());
    }

    @Test
    void removesExpiredPodcastDownload() throws IOException {
        withCacheDirectory();
        Path file = cachePath.resolve("expired.mp3");
        Files.writeString(file, "audio");
        MediaFileEntity expired = download(file, Instant.now().minus(Duration.ofDays(40)));
        when(mediaFileRepository.findByDirectoryEntityIdAndPodcastEpisodeEntityIsNotNullOrderByDateCreatedAsc(cacheDirId))
                .thenReturn(List.of(expired));
        when(watchStatusRepository.existsByPodcastEpisodeEntityIdAndWatchedFalseAndProgressInMillisecondsGreaterThan(
                expired.getPodcastEpisodeEntity().getId(), 0)).thenReturn(false);
        when(cacheCleanupService.clean(any(), any(), any(), anyBoolean()))
                .thenReturn(new CacheCleanupService.CleanupResult(0, 0, 0));

        subject.run();

        assertFalse(Files.exists(file));
        verify(mediaFileRepository).delete(expired);
    }

    @Test
    void dryRunKeepsExpiredPodcastDownload() throws IOException {
        ReflectionTestUtils.setField(subject, "dryRun", true);
        withCacheDirectory();
        Path file = cachePath.resolve("expired.mp3");
        Files.writeString(file, "audio");
        MediaFileEntity expired = download(file, Instant.now().minus(Duration.ofDays(40)));
        when(mediaFileRepository.findByDirectoryEntityIdAndPodcastEpisodeEntityIsNotNullOrderByDateCreatedAsc(cacheDirId))
                .thenReturn(List.of(expired));
        when(watchStatusRepository.existsByPodcastEpisodeEntityIdAndWatchedFalseAndProgressInMillisecondsGreaterThan(
                expired.getPodcastEpisodeEntity().getId(), 0)).thenReturn(false);
        when(cacheCleanupService.clean(any(), any(), any(), anyBoolean()))
                .thenReturn(new CacheCleanupService.CleanupResult(0, 0, 0));

        subject.run();

        assertTrue(Files.exists(file));
        verify(mediaFileRepository, never()).delete(any(MediaFileEntity.class));
    }

    @Test
    void keepsRecentPodcastDownload() throws IOException {
        withCacheDirectory();
        MediaFileEntity recent = download(cachePath.resolve("recent.mp3"), Instant.now().minus(Duration.ofDays(1)));
        when(mediaFileRepository.findByDirectoryEntityIdAndPodcastEpisodeEntityIsNotNullOrderByDateCreatedAsc(cacheDirId))
                .thenReturn(List.of(recent));
        when(cacheCleanupService.clean(any(), any(), any(), anyBoolean()))
                .thenReturn(new CacheCleanupService.CleanupResult(0, 0, 0));

        subject.run();

        verify(mediaFileRepository, never()).delete(any(MediaFileEntity.class));
        verifyNoInteractions(watchStatusRepository);
    }

    @Test
    void keepsExpiredDownloadWhenSomeoneIsMidEpisode() throws IOException {
        withCacheDirectory();
        Path file = cachePath.resolve("in-progress.mp3");
        Files.writeString(file, "audio");
        MediaFileEntity expired = download(file, Instant.now().minus(Duration.ofDays(40)));
        when(mediaFileRepository.findByDirectoryEntityIdAndPodcastEpisodeEntityIsNotNullOrderByDateCreatedAsc(cacheDirId))
                .thenReturn(List.of(expired));
        when(watchStatusRepository.existsByPodcastEpisodeEntityIdAndWatchedFalseAndProgressInMillisecondsGreaterThan(
                expired.getPodcastEpisodeEntity().getId(), 0)).thenReturn(true);
        when(cacheCleanupService.clean(any(), any(), any(), anyBoolean()))
                .thenReturn(new CacheCleanupService.CleanupResult(0, 0, 0));

        subject.run();

        assertTrue(Files.exists(file));
        verify(mediaFileRepository, never()).delete(any(MediaFileEntity.class));
    }

    @Test
    void missingFileOfExpiredDownloadStillDeletesRow() throws IOException {
        withCacheDirectory();
        MediaFileEntity expired = download(cachePath.resolve("gone.mp3"), Instant.now().minus(Duration.ofDays(40)));
        when(mediaFileRepository.findByDirectoryEntityIdAndPodcastEpisodeEntityIsNotNullOrderByDateCreatedAsc(cacheDirId))
                .thenReturn(List.of(expired));
        when(watchStatusRepository.existsByPodcastEpisodeEntityIdAndWatchedFalseAndProgressInMillisecondsGreaterThan(
                expired.getPodcastEpisodeEntity().getId(), 0)).thenReturn(false);
        when(cacheCleanupService.clean(any(), any(), any(), anyBoolean()))
                .thenReturn(new CacheCleanupService.CleanupResult(0, 0, 0));

        subject.run();

        verify(mediaFileRepository).delete(expired);
    }
}
