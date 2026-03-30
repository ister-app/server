package app.ister.worker;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.PreTranscodeRecentlyWatchedData;
import app.ister.core.service.MessageSender;
import app.ister.worker.config.WorkerDiskConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ister.worker.pretranscode.enabled", havingValue = "true", matchIfMissing = true)
public class PreTranscodeScheduler {

    private final MessageSender messageSender;
    private final WorkerDiskConfig workerDiskConfig;

    @Scheduled(cron = "5 */15 * * * *")
    public void schedulePreTranscode() {
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
