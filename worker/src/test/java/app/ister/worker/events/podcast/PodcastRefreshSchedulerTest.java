package app.ister.worker.events.podcast;

import app.ister.core.config.OwnDirectoriesProperties;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PodcastRefreshSchedulerTest {

    @Mock private PodcastRepository podcastRepository;
    @Mock private MessageSender messageSender;
    @Mock private OwnDirectoriesProperties ownDirectories;

    @InjectMocks private PodcastRefreshScheduler subject;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(subject, "refreshMinIntervalMinutes", 30L);
    }

    private static PodcastEntity podcast() {
        PodcastEntity p = PodcastEntity.builder().feedUrl("https://example.org/feed").title("t").active(true).build();
        p.setId(UUID.randomUUID());
        return p;
    }

    @Test
    void nodeWithDirectoriesQueuesRefreshes() {
        when(ownDirectories.names()).thenReturn(List.of("disk1"));
        when(podcastRepository.findByActiveTrue()).thenReturn(List.of(podcast()));

        subject.scheduleRefreshes();

        verify(messageSender).sendPodcastRefreshRequested(any());
    }

    /** A helper node (no own directories) leaves scheduling to the nodes that serve libraries. */
    @Test
    void helperNodeDoesNotSchedule() {
        when(ownDirectories.names()).thenReturn(List.of());

        subject.scheduleRefreshes();

        verifyNoInteractions(podcastRepository, messageSender);
    }
}
