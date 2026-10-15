package app.ister.worker.events.continuewatching;

import app.ister.core.config.ContinueWatchingProperties;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ContinueWatchingRebuildRequestedData;
import app.ister.core.repository.ContinueWatchingRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScheduledJobClaimService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Queues the nightly rebuild of every user's continue-watching list, and fills the table once when
 * it is still empty (the first start after the migration that introduced it).
 *
 * <p>Every node runs the cron, but only the node that wins the {@link ScheduledJobClaimService}
 * claim queues the rebuilds; a rebuild is idempotent, so the claim is about not doing the work N
 * times rather than about correctness. The startup backfill takes the same claim, so N nodes
 * starting together backfill once.
 *
 * <p>The enabled flag is checked at runtime (not via {@code @ConditionalOnProperty}) because bean
 * conditions are frozen at GraalVM native-image build time. Disabling it skips both the nightly
 * rebuild and the one-time startup backfill.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContinueWatchingRebuildScheduler implements ApplicationRunner {

    static final String JOB_NAME = "continue-watching-rebuild";
    /** Shorter than the daily cron, so clock skew between nodes never leaves a night unclaimed. */
    static final Duration CLAIM_INTERVAL = Duration.ofHours(20);

    private final UserRepository userRepository;
    private final ContinueWatchingRepository continueWatchingRepository;
    private final MessageSender messageSender;
    private final ScheduledJobClaimService jobClaims;
    private final ContinueWatchingProperties properties;

    @Scheduled(cron = "${app.ister.server.continue-watching.rebuild-cron:0 30 3 * * *}")
    public void scheduleRebuilds() {
        if (!properties.getRebuild().isEnabled()) {
            log.debug("Continue watching rebuild is disabled, skipping");
            return;
        }
        if (!jobClaims.claim(JOB_NAME, CLAIM_INTERVAL)) {
            return;
        }
        queueRebuildForEveryUser("nightly rebuild");
    }

    /** Backfill: without it the list stays empty until every user has played something again. */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getRebuild().isEnabled()) {
            return;
        }
        if (continueWatchingRepository.count() == 0 && jobClaims.claim(JOB_NAME, CLAIM_INTERVAL)) {
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
