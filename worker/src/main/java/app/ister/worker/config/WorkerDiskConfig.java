package app.ister.worker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the configured disk directories so the worker can send per-disk events.
 * Reads the same {@code app.ister.disk.directories} properties as the disk module.
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "app.ister.disk")
public class WorkerDiskConfig {
    private final List<DiskEntry> directories = new ArrayList<>();

    @Getter
    @Setter
    public static class DiskEntry {
        private String name;
    }
}
