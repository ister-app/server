package app.ister.worker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Podcast feed refresh and auto-download ({@code app.ister.worker.podcast.*}). The refresh cron
 * itself stays a placeholder on the scheduler's {@code @Scheduled}.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.worker.podcast")
public class PodcastProperties {

    /** A feed is not fetched again within this window, even if the cron fires. */
    private long refreshMinIntervalMinutes = 30;

    /** Newest episodes downloaded automatically per feed; older ones download on demand. */
    private int autoDownloadCount = 3;
}
