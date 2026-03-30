package app.ister.transcoder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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
