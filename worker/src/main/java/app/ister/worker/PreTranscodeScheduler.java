package app.ister.worker;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.PreTranscodeRecentlyWatchedData;
import app.ister.core.service.MessageSender;
import app.ister.worker.config.WorkerDiskConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The enabled flag is checked at runtime (not via {@code @ConditionalOnProperty}) because bean
 * conditions are frozen at GraalVM native-image build time.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PreTranscodeScheduler {

    private final MessageSender messageSender;
    private final WorkerDiskConfig workerDiskConfig;

    @Value("${app.ister.worker.pretranscode.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "5 */15 * * * *")
    public void schedulePreTranscode() {
        if (!enabled) {
            log.debug("Pre-transcode scheduling is disabled, skipping");
            return;
        }
        workerDiskConfig.getDirectories().forEach(disk -> {
            log.debug("Sending PRE_TRANSCODE_RECENTLY_WATCHED for disk: {}", disk.getName());
            messageSender.sendPreTranscodeRecentlyWatched(
                    PreTranscodeRecentlyWatchedData.builder()
                            .eventType(EventType.PRE_TRANSCODE_RECENTLY_WATCHED)
                            .diskName(disk.getName())
                            .build(),
                    disk.getName());
        });
    }
}
