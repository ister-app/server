package app.ister.disk.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Getter
@Configuration
@ConfigurationProperties(prefix = "app.ister.server")
public class AppIsterServerConfig {
    private final List<LibraryConfigClass> libraries = new ArrayList<>();
    private final List<DirectoryConfigClass> directories = new ArrayList<>();
}
