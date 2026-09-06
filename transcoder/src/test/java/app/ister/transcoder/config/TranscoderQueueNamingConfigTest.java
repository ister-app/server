package app.ister.transcoder.config;

import app.ister.core.config.DirectoryQueueNames;
import app.ister.core.config.HelperJob;
import app.ister.core.config.HelperProperties;
import app.ister.core.config.OwnDirectoriesProperties;
import app.ister.core.config.TranscoderDisksConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class TranscoderQueueNamingConfigTest {

    private static final String NODE_NAME = "test-node";
    private static final String CACHE_QUEUE_SUFFIX = "." + NODE_NAME + "-cache-directory";

    private OwnDirectoriesProperties directoryConfig;
    private HelperProperties helperProperties;
    private TranscoderDisksConfig disksConfig;

    @BeforeEach
    void setUp() {
        directoryConfig = new OwnDirectoriesProperties();
        helperProperties = new HelperProperties();
        disksConfig = new TranscoderDisksConfig();
    }

    private TranscoderQueueNamingConfig namingConfig() {
        return new TranscoderQueueNamingConfig(
                new DirectoryQueueNames(directoryConfig, helperProperties, disksConfig, NODE_NAME));
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
    void usesLegacyDiskNamesWhenConfiguredAndStillIncludesTheCacheDirectory() {
        addDirectory("movies");
        TranscoderDisksConfig.DiskEntry disk = new TranscoderDisksConfig.DiskEntry();
        disk.setName("disk1");
        disksConfig.getDisks().add(disk);

        assertThat(namingConfig().getTranscodeRequestedQueues()).containsExactly(
                "app.ister.server.TranscodeRequested.disk1",
                "app.ister.server.TranscodeRequested" + CACHE_QUEUE_SUFFIX);
    }

    @Test
    void helperDisksJoinTheOwnDirectories() {
        addDirectory("movies");
        HelperProperties.DiskEntry disk = new HelperProperties.DiskEntry();
        disk.setName("other-tv");
        disk.setJobs(EnumSet.of(HelperJob.TRANSCODE));
        helperProperties.getDisks().add(disk);

        assertThat(namingConfig().getTranscodeRequestedQueues()).containsExactly(
                "app.ister.server.TranscodeRequested.movies",
                "app.ister.server.TranscodeRequested.other-tv",
                "app.ister.server.TranscodeRequested" + CACHE_QUEUE_SUFFIX);
    }

    /** An owner that offloads transcoding stops consuming its own queues but still declares them. */
    @Test
    void offloadingKeepsDeclaringTheOwnQueues() {
        addDirectory("movies");
        helperProperties.setOffloadJobs(EnumSet.of(HelperJob.TRANSCODE));

        TranscoderQueueNamingConfig config = namingConfig();
        assertThat(config.getTranscodeRequestedQueues()).containsExactly(
                "app.ister.server.TranscodeRequested" + CACHE_QUEUE_SUFFIX);
        assertThat(config.declaredNames()).containsExactly("movies", NODE_NAME + "-cache-directory");
    }

    private void addDirectory(String name) {
        OwnDirectoriesProperties.DirectoryEntry entry = new OwnDirectoriesProperties.DirectoryEntry();
        entry.setName(name);
        directoryConfig.getDirectories().add(entry);
    }
}
