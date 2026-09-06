package app.ister.core.eventdata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Periodic snapshot of what a node is processing, published on the status fan-out
 * exchange (see StatusExchangeConfig). Not a Handle-pattern event, so it does not
 * extend MessageData.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeActivityStatusData {
    private String nodeName;
    private Instant timestamp;
    private List<ProcessingItem> processing;
    private long processedCount;
    private long failedCount;
    /** Slow-changing facts about the node (host, directories, disk space); null from older nodes. */
    private NodeFacts facts;

    public NodeActivityStatusData(String nodeName, Instant timestamp, List<ProcessingItem> processing,
                                  long processedCount, long failedCount) {
        this(nodeName, timestamp, processing, processedCount, failedCount, null);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingItem {
        private String queue;
        private String eventType;
        private Instant startedAt;
        /** What is being worked on (file name / entity title); null until the handler reports it. */
        private String subject;
        /** Machine token for the current sub-step (e.g. "probe", "crop"); clients map it to a label. */
        private String step;
        /** Title of the entity the work belongs to (show, movie, album, ...); groups steps on the activity screen. */
        private String context;
        /** Machine token for the kind of {@link #context} ("show", "movie", "album", "book", "podcast", "person", "library"). */
        private String contextType;
        /** Id of the context entity; with {@link #contextType} the grouping key for clients. */
        private String contextId;
        /** Name of the directory (disk) the work runs on. */
        private String directory;
        /** Name of the library the work belongs to. */
        private String library;

        public ProcessingItem(String queue, String eventType, Instant startedAt) {
            this(queue, eventType, startedAt, null, null);
        }

        public ProcessingItem(String queue, String eventType, Instant startedAt, String subject, String step) {
            this(queue, eventType, startedAt, subject, step, null, null, null, null, null);
        }
    }

    /**
     * What a node knows about itself that the activity feed otherwise never carries. Rides
     * on every activity snapshot (the 60s heartbeat at the least), so the disk-space
     * figures are at most a minute old.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeFacts {
        private String hostname;
        private Instant startedAt;
        private String javaVersion;
        private int availableProcessors;
        private long maxMemoryBytes;
        private List<DirectoryFact> directories;
        /** Directories of other nodes this node helps with, as "name" + job families. */
        private List<HelperDiskFact> helperDisks;
        /** Job families this node hands off to helpers for its own directories. */
        private List<String> offloadJobs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DirectoryFact {
        private String name;
        private String path;
        /** "LIBRARY" or "CACHE". */
        private String type;
        private String library;
        /** Null when the path is not mounted / cannot be stat'ed. */
        private Long totalBytes;
        private Long freeBytes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HelperDiskFact {
        private String name;
        private List<String> jobs;
    }
}
