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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import app.ister.core.storage.FileAccess;
import app.ister.core.storage.ObjectStat;

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
public class CacheCleanupScheduler {

    private final NodeService nodeService;
    private final DirectoryRepository directoryRepository;
    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final CacheCleanupService cacheCleanupService;
    private final CacheDirectoryResolver cacheDirectoryResolver;
    private final FileAccess fileAccess;
    private final TransactionTemplate sharedCleanupTransaction;

    static final int SHARED_CACHE_CLEANUP_LOCK_NAMESPACE = 0x43414348; // "CACH"


    @SuppressWarnings("java:S107") // wiring, one collaborator per concern
    public CacheCleanupScheduler(NodeService nodeService, DirectoryRepository directoryRepository,
                                 ImageRepository imageRepository, MediaFileRepository mediaFileRepository,
                                 MediaFileStreamRepository mediaFileStreamRepository, WatchStatusRepository watchStatusRepository,
                                 CacheCleanupService cacheCleanupService, CacheDirectoryResolver cacheDirectoryResolver,
                                 FileAccess fileAccess, PlatformTransactionManager transactionManager) {
        this.nodeService = nodeService;
        this.directoryRepository = directoryRepository;
        this.imageRepository = imageRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.mediaFileStreamRepository = mediaFileStreamRepository;
        this.watchStatusRepository = watchStatusRepository;
        this.cacheCleanupService = cacheCleanupService;
        this.cacheDirectoryResolver = cacheDirectoryResolver;
        this.fileAccess = fileAccess;
        this.sharedCleanupTransaction = new TransactionTemplate(transactionManager);
    }

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
            cleanLocal(cacheDir, allStreamPaths);
        }
        // The cluster-shared S3 cache is attached to every node configured with it; one of them
        // sweeps it per run, decided by a transaction-scoped advisory lock.
        cacheDirectoryResolver.forThisNodeIfAny().filter(DirectoryEntity::isS3).ifPresent(shared ->
                sharedCleanupTransaction.executeWithoutResult(_ -> {
                    if (!directoryRepository.tryLockDirectoryScan(SHARED_CACHE_CLEANUP_LOCK_NAMESPACE, shared.getId())) {
                        log.info("Shared cache {} is being cleaned by another node, skipping", shared.getName());
                        return;
                    }
                    cleanupExpiredPodcastDownloads(shared);
                    cleanShared(shared, allStreamPaths);
                }));
    }

    private Set<String> referencedPaths(DirectoryEntity cacheDir, List<String> allStreamPaths) {
        Set<String> referenced = new HashSet<>(imageRepository.findPathsByDirectoryEntityId(cacheDir.getId()));
        // Media files on the cache directory (downloaded podcast episodes) are referenced too.
        referenced.addAll(mediaFileRepository.findPathsByDirectoryEntityId(cacheDir.getId()));
        String prefix = cacheDir.getPath();
        allStreamPaths.stream().filter(p -> p.startsWith(prefix)).forEach(referenced::add);
        return referenced;
    }

    private void cleanLocal(DirectoryEntity cacheDir, List<String> allStreamPaths) {
        Set<String> referenced = referencedPaths(cacheDir, allStreamPaths);
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

    /** Same rules on a bucket prefix: an object nobody references and older than min-age goes. */
    private void cleanShared(DirectoryEntity cacheDir, List<String> allStreamPaths) {
        Set<String> referenced = referencedPaths(cacheDir, allStreamPaths);
        CacheStore store = cacheDirectoryResolver.storeFor(cacheDir);
        java.time.Instant cutoff = java.time.Instant.now().minus(minAge);
        long deleted = 0;
        long bytes = 0;
        long kept = 0;
        try (var objects = store.list()) {
            for (ObjectStat object : objects.toList()) {
                boolean zombie = !referenced.contains(object.key())
                        && object.lastModified() != null && !object.lastModified().isAfter(cutoff);
                if (!zombie) {
                    kept++;
                    continue;
                }
                if (dryRun) {
                    if (deleted < CacheCleanupService.DRY_RUN_EXAMPLES) {
                        log.info("Cache cleanup [dry-run] would delete {}", object.key());
                    } else {
                        log.debug("Cache cleanup [dry-run] would delete {}", object.key());
                    }
                } else {
                    store.delete(object.key());
                }
                deleted++;
                bytes += object.size();
            }
            log.info("Cache cleanup {} for {}: {} zombie objects ({} MB), {} referenced kept",
                    dryRun ? "[dry-run]" : "[live]", cacheDir.getPath(), deleted, bytes / (1024 * 1024), kept);
        } catch (IOException | RuntimeException e) {
            log.error("Shared cache cleanup failed for {}", cacheDir.getPath(), e);
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
            fileAccess.delete(download.getDirectoryEntity(), download.getPath());
            mediaFileRepository.delete(download);
            log.info("Removed expired podcast download {}", download.getPath());
        } catch (IOException e) {
            log.warn("Could not remove expired podcast download {}: {}", download.getPath(), e.getMessage());
        }
    }
}
