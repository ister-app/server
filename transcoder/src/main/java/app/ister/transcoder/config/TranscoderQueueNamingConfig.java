package app.ister.transcoder.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Stream;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED;
import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_REQUESTED;

@Configuration
@RequiredArgsConstructor
public class TranscoderQueueNamingConfig {

    private final TranscoderDirectoryConfig directoryConfig;
    private final TranscoderDisksConfig transcoderDisksConfig;

    @Value("${app.ister.server.name}")
    private String nodeName;

    /**
     * Media files without a library directory (podcast downloads) live in this node's cache
     * directory and their MediaFileEntity points at it, so transcode events for them are routed
     * to a cache-directory-scoped queue. Without it those events end up on a queue nobody
     * consumes and playback never gets a playlist.
     */
    private String cacheDirName() {
        return nodeName + "-cache-directory";
    }

    private List<String> configuredNames() {
        if (!transcoderDisksConfig.getDisks().isEmpty()) {
            return transcoderDisksConfig.getDisks().stream()
                    .map(TranscoderDisksConfig.DiskEntry::getName)
                    .toList();
        }
        return directoryConfig.getDirectories().stream()
                .map(TranscoderDirectoryConfig.DirectoryEntry::getName)
                .toList();
    }

    /** Suffixes this node transcodes for: its own directories/disks plus its cache directory. */
    public List<String> effectiveNames() {
        return Stream.concat(configuredNames().stream(), Stream.of(cacheDirName())).toList();
    }

    public String[] getTranscodeRequestedQueues() {
        return effectiveNames().stream()
                .map(name -> APP_ISTER_SERVER_TRANSCODE_REQUESTED + "." + name)
                .toArray(String[]::new);
    }

    public String[] getTranscodePassRequestedQueues() {
        return effectiveNames().stream()
                .map(name -> APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED + "." + name)
                .toArray(String[]::new);
    }
}
