package app.ister.transcoder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Getter
@Configuration
@ConfigurationProperties(prefix = "app.ister.disk")
public class TranscoderDirectoryConfig {

    private final List<DirectoryEntry> directories = new ArrayList<>();

    @Getter
    @Setter
    public static class DirectoryEntry {
        private String name;
        private String path;
        private String library;
    }
}
