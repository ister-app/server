package app.ister.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The precomputed continue-watching list ({@code app.ister.server.continue-watching.*}): how far
 * back it looks and the nightly rebuild that repairs it. The rebuild's cron lives in the
 * scheduler's {@code @Scheduled} placeholder; {@link Rebuild#enabled} is checked at runtime
 * because bean conditions are frozen at GraalVM native-image build time.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.server.continue-watching")
public class ContinueWatchingProperties {

    /** How far back the continue-watching list looks; older entries are dropped on rebuild. */
    private int historyDays = 150;

    private Rebuild rebuild = new Rebuild();

    @Getter
    @Setter
    public static class Rebuild {
        /** {@code false} skips both the nightly rebuild and the one-time startup backfill. */
        private boolean enabled = true;
    }
}
