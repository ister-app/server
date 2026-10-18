package app.ister.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** Game library tunables ({@code app.ister.games.*}): the per-user save cloud. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.games")
public class GameProperties {

    /** Save snapshots kept per user per game; older ones are deleted when a new one arrives. */
    private int saveRetention = 10;

    /** The largest save snapshot a client may upload. */
    private DataSize saveMaxBytes = DataSize.ofMegabytes(512);

    /** The largest achievement icon a client may upload. */
    private DataSize achievementIconMaxBytes = DataSize.ofKilobytes(512);
}
