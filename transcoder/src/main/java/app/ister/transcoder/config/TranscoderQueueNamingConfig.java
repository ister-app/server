package app.ister.transcoder.config;

import app.ister.core.config.DirectoryQueueNames;
import app.ister.core.config.HelperJob;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Stream;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED;
import static app.ister.core.MessageQueue.APP_ISTER_SERVER_TRANSCODE_REQUESTED;

/**
 * Transcode queues this node consumes: its own directories (unless it offloads
 * {@link HelperJob#TRANSCODE}), the helper disks configured for transcoding, and its cache
 * directory. See {@link DirectoryQueueNames}.
 */
@Configuration
@RequiredArgsConstructor
public class TranscoderQueueNamingConfig {

    private final DirectoryQueueNames names;

    /** Suffixes this node transcodes for. */
    public List<String> effectiveNames() {
        return names.namesFor(HelperJob.TRANSCODE);
    }

    /** Suffixes this node publishes to for its own files, whether or not it consumes them itself. */
    public List<String> declaredNames() {
        return Stream.concat(
                        Stream.concat(names.ownDirectoryNames().stream(), effectiveNames().stream()),
                        Stream.of(names.cacheDirName()))
                .distinct()
                .toList();
    }

    public String[] getTranscodeRequestedQueues() {
        return names.queues(APP_ISTER_SERVER_TRANSCODE_REQUESTED, HelperJob.TRANSCODE);
    }

    public String[] getTranscodePassRequestedQueues() {
        return names.queues(APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED, HelperJob.TRANSCODE);
    }
}
