package app.ister.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Deprecated predecessor of {@link HelperProperties}: {@code app.ister.transcoder.disks[n].name}
 * named the directories a dedicated transcoder node transcodes for. Still honoured so existing
 * deployments keep working; {@link DirectoryQueueNames} maps it onto helper disks with the
 * {@link HelperJob#TRANSCODE} job and logs a deprecation warning at startup.
 * Prefer {@code app.ister.helper.disks[n].name} (+ {@code jobs}) in new configurations.
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "app.ister.transcoder")
public class TranscoderDisksConfig {

    private final List<DiskEntry> disks = new ArrayList<>();

    @Getter
    @Setter
    public static class DiskEntry {
        private String name;
    }
}
