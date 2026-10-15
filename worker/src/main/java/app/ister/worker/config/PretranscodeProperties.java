package app.ister.worker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Pre-transcode scheduling ({@code app.ister.worker.pretranscode.*}); checked at runtime, not via a bean condition. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.worker.pretranscode")
public class PretranscodeProperties {

    /** {@code false} stops the 15-minute pre-transcode scheduling entirely. */
    private boolean enabled = true;
}
