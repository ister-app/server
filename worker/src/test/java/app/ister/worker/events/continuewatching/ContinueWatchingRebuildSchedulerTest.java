package app.ister.worker.events.continuewatching;

import app.ister.core.config.ContinueWatchingProperties;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ContinueWatchingRebuildRequestedData;
import app.ister.core.repository.ContinueWatchingRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScheduledJobClaimService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContinueWatchingRebuildSchedulerTest {

    @InjectMocks
    private ContinueWatchingRebuildScheduler subject;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContinueWatchingRepository continueWatchingRepository;

    @Mock
    private MessageSender messageSender;

    @Mock
    private ScheduledJobClaimService jobClaims;

    @Spy
    private ContinueWatchingProperties properties = new ContinueWatchingProperties();

    private UserEntity user(UUID id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        return user;
    }

    private void claimWon(boolean won) {
        when(jobClaims.claim(ContinueWatchingRebuildScheduler.JOB_NAME, ContinueWatchingRebuildScheduler.CLAIM_INTERVAL))
                .thenReturn(won);
    }

    @Test
    void scheduleRebuildsQueuesOneEventPerUser() {
        claimWon(true);
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        when(userRepository.findAll()).thenReturn(List.of(user(userId1), user(userId2)));

        subject.scheduleRebuilds();

        ArgumentCaptor<ContinueWatchingRebuildRequestedData> captor =
                ArgumentCaptor.forClass(ContinueWatchingRebuildRequestedData.class);
        verify(messageSender, times(2)).sendContinueWatchingRebuildRequested(captor.capture());
        assertEquals(EventType.CONTINUE_WATCHING_REBUILD_REQUESTED, captor.getAllValues().get(0).getEventType());
        assertEquals(userId1, captor.getAllValues().get(0).getUserId());
        assertEquals(userId2, captor.getAllValues().get(1).getUserId());
    }

    /** Another node already queued tonight's rebuilds. */
    @Test
    void scheduleRebuildsDoesNothingWhenAnotherNodeClaimedTheRun() {
        claimWon(false);

        subject.scheduleRebuilds();

        verifyNoInteractions(messageSender, userRepository);
    }

    @Test
    void runBackfillsOnlyWhenTheTableIsEmpty() {
        claimWon(true);
        when(continueWatchingRepository.count()).thenReturn(0L);
        when(userRepository.findAll()).thenReturn(List.of(user(UUID.randomUUID())));

        subject.run(null);

        verify(messageSender, times(1)).sendContinueWatchingRebuildRequested(any());
    }

    @Test
    void runDoesNothingWhenTheTableHasRows() {
        when(continueWatchingRepository.count()).thenReturn(5L);

        subject.run(null);

        verifyNoInteractions(messageSender, userRepository, jobClaims);
    }

    /** Three nodes starting against an empty table: only the claim winner backfills. */
    @Test
    void runDoesNotBackfillWhenAnotherNodeClaimedIt() {
        claimWon(false);
        when(continueWatchingRepository.count()).thenReturn(0L);

        subject.run(null);

        verifyNoInteractions(messageSender, userRepository);
    }

    @Test
    void disabledSkipsBothTheNightlyRebuildAndTheStartupBackfill() {
        properties.getRebuild().setEnabled(false);

        subject.scheduleRebuilds();
        subject.run(null);

        verifyNoInteractions(messageSender, userRepository, continueWatchingRepository, jobClaims);
    }
}
