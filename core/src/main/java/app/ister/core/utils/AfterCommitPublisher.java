package app.ister.core.utils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes after the surrounding transaction commits (immediately when there is none).
 * Every event carries an entity id the consumer looks up, so publishing mid-transaction
 * races the commit: the consumer reads before the write is visible and skips silently or
 * dead-letters, flakily. The same race exists for deletes — a consumer reading before the
 * delete commits sees rows that are about to disappear.
 *
 * <p>Do not call this from inside an {@code afterCommit} callback: a synchronization
 * registered at that point is never invoked by Spring, so the message is silently lost.
 * That is also why this wrap must stay at the call sites and not move into
 * {@code MessageSender} itself.
 */
public final class AfterCommitPublisher {

    private AfterCommitPublisher() {
    }

    public static void publishAfterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
