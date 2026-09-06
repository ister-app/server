package app.ister.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryQueueNamesTest {

    private static final String NODE = "test-node";
    private static final String CACHE = NODE + "-cache-directory";
    private static final String BASE = "app.ister.server.DetectSegments";

    private OwnDirectoriesProperties own;
    private HelperProperties helper;
    private TranscoderDisksConfig legacy;

    @BeforeEach
    void setUp() {
        own = new OwnDirectoriesProperties();
        helper = new HelperProperties();
        legacy = new TranscoderDisksConfig();
    }

    private DirectoryQueueNames names() {
        return new DirectoryQueueNames(own, helper, legacy, NODE);
    }

    @Test
    void plainQueuesAreOwnDirectoriesPlusCacheDirectory() {
        addOwn("movies");
        addOwn("tv");

        assertThat(names().queues(BASE)).containsExactly(
                BASE + ".movies", BASE + ".tv", BASE + "." + CACHE);
    }

    @Test
    void helperCapableQueuesEqualPlainQueuesWithoutHelperConfig() {
        addOwn("movies");

        assertThat(names().queues(BASE, HelperJob.DETECT_SEGMENTS))
                .containsExactly(BASE + ".movies", BASE + "." + CACHE);
    }

    @Test
    void helperDisksAreAddedForTheirJobsOnly() {
        addOwn("movies");
        addHelper("other-tv", EnumSet.of(HelperJob.DETECT_SEGMENTS));
        addHelper("other-movies", EnumSet.noneOf(HelperJob.class)); // inherits default: all jobs

        DirectoryQueueNames names = names();
        assertThat(names.namesFor(HelperJob.DETECT_SEGMENTS)).containsExactly("movies", "other-tv", "other-movies", CACHE);
        assertThat(names.namesFor(HelperJob.SUBTITLES)).containsExactly("movies", "other-movies", CACHE);
        assertThat(names.helperDirectoryNames(HelperJob.TRANSCODE)).containsExactly("other-movies");
        assertThat(names.allHelperDirectoryNames()).containsExactlyInAnyOrder("other-tv", "other-movies");
    }

    @Test
    void nodeWideJobsDefaultAppliesToDisksWithoutOwnJobs() {
        helper.setJobs(EnumSet.of(HelperJob.TRANSCODE));
        addHelper("other", EnumSet.noneOf(HelperJob.class));

        assertThat(names().helperDirectoryNames(HelperJob.TRANSCODE)).containsExactly("other");
        assertThat(names().helperDirectoryNames(HelperJob.SUBTITLES)).isEmpty();
    }

    /** An offloaded job drops the own directories but never the cache directory (podcasts). */
    @Test
    void offloadDropsOwnDirectoriesButKeepsCacheDirectory() {
        addOwn("movies");
        helper.setOffloadJobs(EnumSet.of(HelperJob.DETECT_SEGMENTS));

        DirectoryQueueNames names = names();
        assertThat(names.queues(BASE, HelperJob.DETECT_SEGMENTS)).containsExactly(BASE + "." + CACHE);
        assertThat(names.queues(BASE, HelperJob.SUBTITLES)).containsExactly(BASE + ".movies", BASE + "." + CACHE);
        // Plain events are unaffected by offloading.
        assertThat(names.queues(BASE)).containsExactly(BASE + ".movies", BASE + "." + CACHE);
    }

    /** The deprecated transcoder disks list keeps its old meaning: replace the own directories, TRANSCODE only. */
    @Test
    void legacyTranscoderDisksMapToTranscodeOnlyAndReplaceOwnDirectories() {
        addOwn("movies");
        TranscoderDisksConfig.DiskEntry disk = new TranscoderDisksConfig.DiskEntry();
        disk.setName("disk1");
        legacy.getDisks().add(disk);

        DirectoryQueueNames names = names();
        assertThat(names.namesFor(HelperJob.TRANSCODE)).containsExactly("disk1", CACHE);
        assertThat(names.namesFor(HelperJob.DETECT_SEGMENTS)).containsExactly("movies", CACHE);
    }

    @Test
    void duplicateNamesCollapse() {
        addOwn("movies");
        addHelper("movies", EnumSet.allOf(HelperJob.class));

        assertThat(names().queues(BASE, HelperJob.TRANSCODE)).containsExactly(BASE + ".movies", BASE + "." + CACHE);
    }

    private void addOwn(String name) {
        OwnDirectoriesProperties.DirectoryEntry entry = new OwnDirectoriesProperties.DirectoryEntry();
        entry.setName(name);
        own.getDirectories().add(entry);
    }

    private void addHelper(String name, EnumSet<HelperJob> jobs) {
        HelperProperties.DiskEntry entry = new HelperProperties.DiskEntry();
        entry.setName(name);
        entry.setJobs(jobs);
        helper.getDisks().add(entry);
    }
}
