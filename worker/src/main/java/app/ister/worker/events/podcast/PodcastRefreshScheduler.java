package app.ister.worker.events.podcast;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.PodcastRefreshRequestedData;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodically queues a refresh for every active podcast. The scheduler runs on every node, so
 * the lastRefreshedAt guard keeps N nodes from queueing N refreshes each sweep; a rare duplicate
 * refresh is harmless (episode sync is idempotent on guid).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PodcastRefreshScheduler {

    private final PodcastRepository podcastRepository;
    private final MessageSender messageSender;

    @Value("${app.ister.worker.podcast.refresh-min-interval-minutes:30}")
    private long refreshMinIntervalMinutes;

    @Scheduled(cron = "${app.ister.worker.podcast.refresh-cron:0 10 * * * *}")
    public void scheduleRefreshes() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(refreshMinIntervalMinutes));
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
