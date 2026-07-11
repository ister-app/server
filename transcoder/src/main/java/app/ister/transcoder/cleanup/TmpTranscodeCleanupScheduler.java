package app.ister.transcoder.cleanup;

import app.ister.core.repository.MediaFileRepository;
import app.ister.transcoder.HlsTranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Daily sweep that removes transcode working directories under {@code tmpDir} that no live consumer
 * needs (orphans, or idle directories with no active FFmpeg pass). Runs on every node and only
 * touches the local {@code tmpDir}, so live pass state can be read from the in-memory
 * {@link HlsTranscodeService}.
 * <p>
 * The enabled flag is checked at runtime (not via {@code @ConditionalOnProperty}) because bean
 * conditions are frozen at GraalVM native-image build time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TmpTranscodeCleanupScheduler {

    private final MediaFileRepository mediaFileRepository;
    private final HlsTranscodeService transcodeService;
    private final TmpCleanupService tmpCleanupService;
    private final Clock clock = Clock.systemUTC();

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @Value("${app.ister.server.cache-cleanup.enabled:true}")
    private boolean enabled;

    @Value("${app.ister.server.cache-cleanup.dry-run:true}")
    private boolean dryRun;

    @Value("${app.ister.server.cache-cleanup.min-age:24h}")
    private Duration minAge;

    @Scheduled(cron = "${app.ister.server.cache-cleanup.cron:0 30 4 * * *}")
    public void run() {
        if (!enabled) {
            log.debug("Tmp cleanup disabled, skipping");
            return;
        }
        try {
            TmpCleanupService.CleanupResult res = tmpCleanupService.clean(
                    Path.of(tmpDir),
                    mediaFileRepository::existsById,
                    transcodeService::hasActivePassForFile,
                    clock, minAge, dryRun);
            log.info("Tmp cleanup {} for {}: {} transcode dirs ({} MB), {} kept",
                    dryRun ? "[dry-run]" : "[live]", tmpDir,
                    res.dirsDeleted(), res.bytesFreed() / (1024 * 1024), res.dirsKept());
        } catch (IOException e) {
            log.error("Tmp cleanup failed for {}", tmpDir, e);
        }
    }
}
