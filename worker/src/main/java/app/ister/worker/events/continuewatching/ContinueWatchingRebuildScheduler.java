package app.ister.worker.events.continuewatching;

import app.ister.core.entity.UserEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ContinueWatchingRebuildRequestedData;
import app.ister.core.repository.ContinueWatchingRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Queues the nightly rebuild of every user's continue-watching list, and fills the table once when
 * it is still empty (the first start after the migration that introduced it).
 *
 * <p>Like the other schedulers this runs on every node; a rebuild is idempotent, so a node queueing
 * a second one costs some work and changes nothing.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ister.server.continue-watching.rebuild.enabled", havingValue = "true", matchIfMissing = true)
public class ContinueWatchingRebuildScheduler implements ApplicationRunner {

    private final UserRepository userRepository;
    private final ContinueWatchingRepository continueWatchingRepository;
    private final MessageSender messageSender;

    @Scheduled(cron = "${app.ister.server.continue-watching.rebuild-cron:0 30 3 * * *}")
    public void scheduleRebuilds() {
        queueRebuildForEveryUser("nightly rebuild");
    }

    /** Backfill: without it the list stays empty until every user has played something again. */
    @Override
    public void run(ApplicationArguments args) {
        if (continueWatchingRepository.count() == 0) {
            queueRebuildForEveryUser("backfill of the empty continue_watching table");
        }
    }

    private void queueRebuildForEveryUser(String reason) {
        for (UserEntity user : userRepository.findAll()) {
            log.debug("Queueing continue watching rebuild for user {} ({})", user.getId(), reason);
            messageSender.sendContinueWatchingRebuildRequested(ContinueWatchingRebuildRequestedData.builder()
                    .eventType(EventType.CONTINUE_WATCHING_REBUILD_REQUESTED)
                    .userId(user.getId())
                    .build());
        }
    }
}
