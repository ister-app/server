package app.ister.core.utils;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AfterCommitPublisherTest {

    @Test
    void runsImmediatelyWithoutActiveTransaction() {
        AtomicInteger runs = new AtomicInteger();

        AfterCommitPublisher.publishAfterCommit(runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    @Test
    void defersUntilAfterCommitInsideTransaction() {
        AtomicInteger runs = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        try {
            AfterCommitPublisher.publishAfterCommit(runs::incrementAndGet);
            assertEquals(0, runs.get());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertEquals(1, runs.get());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
