package app.ister.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * The names of the directories this node owns ({@code app.ister.disk.directories[n].name}).
 * The disk module binds the full entries (path, library) in its own config class; core only
 * needs the names, to build the directory-scoped queue names in {@link DirectoryQueueNames}.
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "app.ister.disk")
public class OwnDirectoriesProperties {

    private final List<DirectoryEntry> directories = new ArrayList<>();

    public List<String> names() {
        return directories.stream().map(DirectoryEntry::getName).toList();
    }

    @Getter
    @Setter
    public static class DirectoryEntry {
        private String name;
        private String path;
        private String library;
    }
}
