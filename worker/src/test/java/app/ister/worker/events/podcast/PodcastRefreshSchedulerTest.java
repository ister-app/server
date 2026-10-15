package app.ister.worker.events.podcast;

import app.ister.core.config.OwnDirectoriesProperties;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScheduledJobClaimService;
import app.ister.worker.config.PodcastProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PodcastRefreshSchedulerTest {

    @Mock private PodcastRepository podcastRepository;
    @Mock private MessageSender messageSender;
    @Mock private OwnDirectoriesProperties ownDirectories;
    @Mock private ScheduledJobClaimService jobClaims;
    @Spy private PodcastProperties properties = new PodcastProperties();

    @InjectMocks private PodcastRefreshScheduler subject;

    private static PodcastEntity podcast() {
        PodcastEntity p = PodcastEntity.builder().feedUrl("https://example.org/feed").title("t").active(true).build();
        p.setId(UUID.randomUUID());
        return p;
    }

    @Test
    void nodeWithDirectoriesQueuesRefreshes() {
        when(ownDirectories.names()).thenReturn(List.of("disk1"));
        when(jobClaims.claim(PodcastRefreshScheduler.JOB_NAME, Duration.ofMinutes(30))).thenReturn(true);
        when(podcastRepository.findByActiveTrue()).thenReturn(List.of(podcast()));

        subject.scheduleRefreshes();

        verify(messageSender).sendPodcastRefreshRequested(any());
    }

    /** A feed another node fetched inside the window is left alone even by the claim winner. */
    @Test
    void recentlyRefreshedFeedIsSkipped() {
        when(ownDirectories.names()).thenReturn(List.of("disk1"));
        when(jobClaims.claim(PodcastRefreshScheduler.JOB_NAME, Duration.ofMinutes(30))).thenReturn(true);
        PodcastEntity fresh = podcast();
        fresh.setLastRefreshedAt(Instant.now().minus(Duration.ofMinutes(5)));
        when(podcastRepository.findByActiveTrue()).thenReturn(List.of(fresh));

        subject.scheduleRefreshes();

        verify(messageSender, never()).sendPodcastRefreshRequested(any());
    }

    /** Another library-serving node already queued this sweep. */
    @Test
    void nodeThatLosesTheClaimDoesNotSchedule() {
        when(ownDirectories.names()).thenReturn(List.of("disk1"));
        when(jobClaims.claim(PodcastRefreshScheduler.JOB_NAME, Duration.ofMinutes(30))).thenReturn(false);

        subject.scheduleRefreshes();

        verifyNoInteractions(podcastRepository, messageSender);
    }

    /** A helper node (no own directories) leaves scheduling to the nodes that serve libraries. */
    @Test
    void helperNodeDoesNotSchedule() {
        when(ownDirectories.names()).thenReturn(List.of());

        subject.scheduleRefreshes();

        verifyNoInteractions(podcastRepository, messageSender, jobClaims);
    }
}
