package app.ister.disk.cleanup;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.NodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Daily sweep that removes zombie files from this node's image cache directory. Runs on every node
 * and only touches the cache directory owned by the node it runs on.
 * <p>
 * The enabled flag is checked at runtime (not via {@code @ConditionalOnProperty}) because bean
 * conditions are frozen at GraalVM native-image build time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheCleanupScheduler {

    private final NodeService nodeService;
    private final DirectoryRepository directoryRepository;
    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final CacheCleanupService cacheCleanupService;

    @Value("${app.ister.server.cache-cleanup.enabled:true}")
    private boolean enabled;

    @Value("${app.ister.server.cache-cleanup.dry-run:true}")
    private boolean dryRun;

    @Value("${app.ister.server.cache-cleanup.min-age:24h}")
    private Duration minAge;

    /** Downloaded podcast episodes older than this are removed (re-downloadable on demand). */
    @Value("${app.ister.server.cache-cleanup.podcast-retention-days:30}")
    private long podcastRetentionDays;

    @Scheduled(cron = "${app.ister.server.cache-cleanup.cron:0 30 4 * * *}")
    public void run() {
        if (!enabled) {
            log.debug("Cache cleanup disabled, skipping");
            return;
        }
        NodeEntity node = nodeService.getOrCreateNodeEntityForThisNode();
        List<DirectoryEntity> cacheDirs = directoryRepository
                .findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, node);
        if (cacheDirs.isEmpty()) {
            log.warn("Cache cleanup: no cache directory for node {}", node.getName());
            return;
        }
        // Stream (subtitle) paths are not scoped by directory id, so fetch once and filter by prefix.
        List<String> allStreamPaths = mediaFileStreamRepository.findAllNonNullPaths();
        for (DirectoryEntity cacheDir : cacheDirs) {
            cleanupExpiredPodcastDownloads(cacheDir);
            Set<String> referenced = new HashSet<>(imageRepository.findPathsByDirectoryEntityId(cacheDir.getId()));
            // Media files on the cache directory (downloaded podcast episodes) are referenced too.
            referenced.addAll(mediaFileRepository.findPathsByDirectoryEntityId(cacheDir.getId()));
            String prefix = cacheDir.getPath();
            allStreamPaths.stream().filter(p -> p.startsWith(prefix)).forEach(referenced::add);
            try {
                CacheCleanupService.CleanupResult res = cacheCleanupService.clean(
                        Path.of(cacheDir.getPath()), referenced, minAge, dryRun);
                log.info("Cache cleanup {} for {}: {} zombie files ({} MB), {} referenced kept",
                        dryRun ? "[dry-run]" : "[live]", cacheDir.getPath(),
                        res.filesDeleted(), res.bytesFreed() / (1024 * 1024), res.filesKept());
            } catch (IOException e) {
                log.error("Cache cleanup failed for {}", cacheDir.getPath(), e);
            }
        }
    }

    /**
     * Retention for downloaded podcast audio: downloads older than the retention window are
     * removed (row + file), UNLESS someone is mid-episode. The episode entity itself stays, so
     * playback later simply re-downloads on demand. Honors the same dry-run flag as the sweep.
     */
    private void cleanupExpiredPodcastDownloads(DirectoryEntity cacheDir) {
        java.time.Instant cutoff = java.time.Instant.now().minus(Duration.ofDays(podcastRetentionDays));
        List<MediaFileEntity> downloads = mediaFileRepository
                .findByDirectoryEntityIdAndPodcastEpisodeEntityIsNotNullOrderByDateCreatedAsc(cacheDir.getId());
        for (MediaFileEntity download : downloads) {
            if (isExpired(download, cutoff)) {
                removeExpiredDownload(download);
            }
        }
    }

    private boolean isExpired(MediaFileEntity download, java.time.Instant cutoff) {
        if (download.getDateCreated().isAfter(cutoff)) {
            return false;
        }
        java.util.UUID episodeId = download.getPodcastEpisodeEntity().getId();
        // Someone is mid-episode: keep the file so playback does not stall on a re-download.
        return !watchStatusRepository
                .existsByPodcastEpisodeEntityIdAndWatchedFalseAndProgressInMillisecondsGreaterThan(episodeId, 0);
    }

    private void removeExpiredDownload(MediaFileEntity download) {
        if (dryRun) {
            log.info("Cache cleanup [dry-run] would remove expired podcast download {}", download.getPath());
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(Path.of(download.getPath()));
            mediaFileRepository.delete(download);
            log.info("Removed expired podcast download {}", download.getPath());
        } catch (IOException e) {
            log.warn("Could not remove expired podcast download {}: {}", download.getPath(), e.getMessage());
        }
    }
}
