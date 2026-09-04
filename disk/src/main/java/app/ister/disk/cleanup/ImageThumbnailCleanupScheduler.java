package app.ister.disk.cleanup;

import app.ister.core.entity.ImageEntity;
import app.ister.core.repository.ImageRepository;
import app.ister.disk.ImageThumbnailCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * Daily sweep of this node's downscaled-artwork cache, on the same schedule and dry-run flag as
 * the other cleanups.
 * <p>
 * Its own idle window rather than the shared 24h {@code min-age}: a thumbnail is touched on every
 * cache hit, so idle here means "nobody browsed this artwork in a month", and regenerating costs
 * one decode. The enabled flag is checked at runtime (not via {@code @ConditionalOnProperty})
 * because bean conditions are frozen at GraalVM native-image build time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageThumbnailCleanupScheduler {

    private final ImageRepository imageRepository;
    private final ImageThumbnailCleanupService cleanupService;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @Value("${app.ister.server.cache-cleanup.enabled:true}")
    private boolean enabled;

    @Value("${app.ister.server.cache-cleanup.dry-run:true}")
    private boolean dryRun;

    @Value("${app.ister.server.image-thumbnail.max-idle:30d}")
    private Duration maxIdle;

    @Scheduled(cron = "${app.ister.server.cache-cleanup.cron:0 30 4 * * *}")
    public void run() {
        if (!enabled) {
            log.debug("Thumbnail cleanup disabled, skipping");
            return;
        }
        Path root = Path.of(tmpDir, ImageThumbnailCache.THUMBNAIL_DIR);
        try {
            ImageThumbnailCleanupService.CleanupResult result =
                    cleanupService.clean(root, maxIdle, this::liveImageIds, dryRun);
            if (result.filesDeleted() > 0 || result.filesKept() > 0) {
                log.info("Thumbnail cleanup {} for {}: {} removed ({} MB), {} kept",
                        dryRun ? "[dry-run]" : "[live]", root,
                        result.filesDeleted(), result.bytesFreed() / (1024 * 1024), result.filesKept());
            }
        } catch (IOException e) {
            log.error("Thumbnail cleanup failed for {}", root, e);
        }
    }

    private Set<UUID> liveImageIds(Set<UUID> candidates) {
        Set<UUID> live = new HashSet<>();
        StreamSupport.stream(imageRepository.findAllById(candidates).spliterator(), false)
                .map(ImageEntity::getId)
                .forEach(live::add);
        return live;
    }
}
