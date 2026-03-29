package app.ister.transcoder.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED;
import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_REQUESTED;

@Configuration
@RequiredArgsConstructor
public class TranscoderQueueNamingConfig {

    private final TranscoderDirectoryConfig config;

    public String[] getTranscodeRequestedQueues() {
        return config.getDirectories().stream()
                .map(dir -> APP_ISTER_SERVER_TRANSCODE_REQUESTED + "." + dir.getName())
                .toArray(String[]::new);
    }

    public String[] getTranscodePassRequestedQueues() {
        return config.getDirectories().stream()
                .map(dir -> APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED + "." + dir.getName())
                .toArray(String[]::new);
    }
}
