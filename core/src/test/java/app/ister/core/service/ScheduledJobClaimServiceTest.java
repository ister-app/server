package app.ister.core.service;

import app.ister.core.repository.ScheduledJobRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledJobClaimServiceTest {

    @Mock
    private ScheduledJobRunRepository repository;

    /** A mocked manager makes the TransactionTemplate run its callback inline. */
    @Mock
    private PlatformTransactionManager transactionManager;

    @Test
    void claimRunsTheUpsertInATransactionAndReportsTheOutcome() {
        when(repository.tryClaim("nightly", Duration.ofHours(20))).thenReturn(true, false);
        ScheduledJobClaimService subject = new ScheduledJobClaimService(repository, transactionManager);

        assertTrue(subject.claim("nightly", Duration.ofHours(20)));
        assertFalse(subject.claim("nightly", Duration.ofHours(20)));

        verify(transactionManager, org.mockito.Mockito.times(2)).getTransaction(org.mockito.ArgumentMatchers.any());
    }
}
