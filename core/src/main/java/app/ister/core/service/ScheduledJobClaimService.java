package app.ister.core.service;

import app.ister.core.repository.ScheduledJobRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

/**
 * Lets a {@code @Scheduled} job that fans work out for the whole cluster run on one node per
 * period. Every node fires the same cron; the first to {@link #claim} a job name wins, the others
 * see a {@code last_run_at} inside the interval and skip. Unlike the transaction-scoped advisory
 * locks used by the sweeps, the claim outlives the transaction, so a node whose cron fires a few
 * seconds later than the winner's is kept out as well.
 */
@Service
@Slf4j
public class ScheduledJobClaimService {

    private final ScheduledJobRunRepository scheduledJobRunRepository;
    private final TransactionTemplate transaction;

    public ScheduledJobClaimService(ScheduledJobRunRepository scheduledJobRunRepository,
                                    PlatformTransactionManager transactionManager) {
        this.scheduledJobRunRepository = scheduledJobRunRepository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param minInterval how long a claim keeps other nodes out; pick it shorter than the cron
     *                    period, so clock skew between nodes cannot make a period go unclaimed
     * @return whether this node should run the job now
     */
    public boolean claim(String jobName, Duration minInterval) {
        boolean won = Boolean.TRUE.equals(transaction.execute(_ ->
                scheduledJobRunRepository.tryClaim(jobName, minInterval)));
        if (!won) {
            log.debug("Scheduled job {} already claimed by another node within {}", jobName, minInterval);
        }
        return won;
    }
}
