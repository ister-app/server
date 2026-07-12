package app.ister.transcoder.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TranscoderQueueNamingConfigTest {

    private static final String NODE_NAME = "test-node";
    private static final String CACHE_QUEUE_SUFFIX = "." + NODE_NAME + "-cache-directory";

    private TranscoderDirectoryConfig directoryConfig;
    private TranscoderDisksConfig disksConfig;

    @BeforeEach
    void setUp() {
        directoryConfig = new TranscoderDirectoryConfig();
        disksConfig = new TranscoderDisksConfig();
    }

    private TranscoderQueueNamingConfig namingConfig() {
        TranscoderQueueNamingConfig config = new TranscoderQueueNamingConfig(directoryConfig, disksConfig);
        ReflectionTestUtils.setField(config, "nodeName", NODE_NAME);
        return config;
    }

    /**
     * Podcast downloads have no library directory: their MediaFileEntity points at the node's
     * cache directory, so the node must consume the cache-directory transcode queues or their
     * playlists are never generated.
     */
    @Test
    void includesTheCacheDirectoryQueues() {
        addDirectory("movies");

        TranscoderQueueNamingConfig config = namingConfig();

        assertThat(config.getTranscodeRequestedQueues()).containsExactly(
                "app.ister.server.TranscodeRequested.movies",
                "app.ister.server.TranscodeRequested" + CACHE_QUEUE_SUFFIX);
        assertThat(config.getTranscodePassRequestedQueues()).containsExactly(
                "app.ister.server.TranscodePassRequested.movies",
                "app.ister.server.TranscodePassRequested" + CACHE_QUEUE_SUFFIX);
    }

    @Test
    void usesDiskNamesWhenConfiguredAndStillIncludesTheCacheDirectory() {
        addDirectory("movies");
        TranscoderDisksConfig.DiskEntry disk = new TranscoderDisksConfig.DiskEntry();
        disk.setName("disk1");
        disksConfig.getDisks().add(disk);

        assertThat(namingConfig().getTranscodeRequestedQueues()).containsExactly(
                "app.ister.server.TranscodeRequested.disk1",
                "app.ister.server.TranscodeRequested" + CACHE_QUEUE_SUFFIX);
    }

    private void addDirectory(String name) {
        TranscoderDirectoryConfig.DirectoryEntry entry = new TranscoderDirectoryConfig.DirectoryEntry();
        entry.setName(name);
        directoryConfig.getDirectories().add(entry);
    }
}
