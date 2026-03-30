package app.ister.transcoder.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED;
import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_REQUESTED;

@Configuration
@RequiredArgsConstructor
public class TranscoderQueueNamingConfig {

    private final TranscoderDirectoryConfig directoryConfig;
    private final TranscoderDisksConfig transcoderDisksConfig;

    private List<String> effectiveNames() {
        if (!transcoderDisksConfig.getDisks().isEmpty()) {
            return transcoderDisksConfig.getDisks().stream()
                    .map(TranscoderDisksConfig.DiskEntry::getName)
                    .toList();
        }
        return directoryConfig.getDirectories().stream()
                .map(TranscoderDirectoryConfig.DirectoryEntry::getName)
                .toList();
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
