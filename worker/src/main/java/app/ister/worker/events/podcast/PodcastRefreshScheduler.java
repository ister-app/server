package app.ister.worker.events.podcast;

import app.ister.core.config.OwnDirectoriesProperties;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.PodcastRefreshRequestedData;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScheduledJobClaimService;
import app.ister.worker.config.PodcastProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodically queues a refresh for every active podcast. Only nodes that serve libraries
 * schedule (a helper node, see {@code app.ister.helper.*}, has no directories and skips); among
 * those, one node per {@code refresh-min-interval-minutes} wins the {@link ScheduledJobClaimService}
 * claim and queues the sweep. The per-podcast lastRefreshedAt guard then skips feeds fetched
 * recently by any node, and the handler's per-podcast advisory lock catches what still coincides.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PodcastRefreshScheduler {

    static final String JOB_NAME = "podcast-refresh";

    private final PodcastRepository podcastRepository;
    private final MessageSender messageSender;
    private final OwnDirectoriesProperties ownDirectories;
    private final ScheduledJobClaimService jobClaims;
    private final PodcastProperties properties;

    @Scheduled(cron = "${app.ister.worker.podcast.refresh-cron:0 10 * * * *}")
    public void scheduleRefreshes() {
        if (ownDirectories.names().isEmpty()) {
            log.debug("No own directories on this node, leaving podcast refreshes to the nodes that serve libraries");
            return;
        }
        Duration minInterval = Duration.ofMinutes(properties.getRefreshMinIntervalMinutes());
        if (!jobClaims.claim(JOB_NAME, minInterval)) {
            return;
        }
        Instant threshold = Instant.now().minus(minInterval);
        podcastRepository.findByActiveTrue().stream()
                .filter(podcast -> podcast.getLastRefreshedAt() == null
                        || podcast.getLastRefreshedAt().isBefore(threshold))
                .forEach(podcast -> {
                    log.debug("Queueing podcast refresh for {}", podcast.getTitle());
                    messageSender.sendPodcastRefreshRequested(PodcastRefreshRequestedData.builder()
                            .eventType(EventType.PODCAST_REFRESH_REQUESTED)
                            .podcastId(podcast.getId())
                            .build());
                });
    }
}
