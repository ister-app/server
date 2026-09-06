package app.ister.api.dto;

import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.QueueStatsStatusData;
import app.ister.core.eventdata.TranscodeActivityStatusData;

import java.util.List;

/**
 * GraphQL view of one status update from one node; exactly one of
 * processing/queueStats/failure/transcodes is set, matching {@code type}. Keeps the
 * GraphQL schema decoupled from the AMQP payloads in core.
 */
public record ServerActivityEvent(
        ServerActivityEventType type,
        String nodeName,
        String timestamp,
        List<ProcessingItem> processing,
        Long processedCount,
        Long failedCount,
        List<QueueStat> queueStats,
        EventFailure failure,
        List<TranscodePass> transcodes,
        NodeInfo nodeInfo) {

    public enum ServerActivityEventType {NODE_ACTIVITY, QUEUE_STATS, FAILURE, TRANSCODE_ACTIVITY}

    public record ProcessingItem(String queue, String eventType, String startedAt, String subject, String step,
                                 String context, String contextType, String contextId, String directory,
                                 String library) {
        static ProcessingItem from(NodeActivityStatusData.ProcessingItem item) {
            return new ProcessingItem(item.getQueue(), item.getEventType(), String.valueOf(item.getStartedAt()),
                    item.getSubject(), item.getStep(), item.getContext(), item.getContextType(),
                    item.getContextId(), item.getDirectory(), item.getLibrary());
        }
    }

    /** Slow-changing facts a node reports about itself; null for events from older nodes. */
    public record NodeInfo(String hostname, String startedAt, String javaVersion, Integer availableProcessors,
                           Double maxMemoryBytes, List<NodeDirectory> directories, List<HelperDisk> helperDisks,
                           List<String> offloadJobs) {
        static NodeInfo from(NodeActivityStatusData.NodeFacts facts) {
            if (facts == null) {
                return null;
            }
            return new NodeInfo(facts.getHostname(),
                    facts.getStartedAt() == null ? null : String.valueOf(facts.getStartedAt()),
                    facts.getJavaVersion(), facts.getAvailableProcessors(), (double) facts.getMaxMemoryBytes(),
                    facts.getDirectories() == null ? List.of()
                            : facts.getDirectories().stream().map(NodeDirectory::from).toList(),
                    facts.getHelperDisks() == null ? List.of()
                            : facts.getHelperDisks().stream().map(HelperDisk::from).toList(),
                    facts.getOffloadJobs() == null ? List.of() : facts.getOffloadJobs());
        }
    }

    /** Byte counts travel as GraphQL Float: Int is 32-bit and disks are not. */
    public record NodeDirectory(String name, String path, String type, String library, Double totalBytes,
                                Double freeBytes) {
        static NodeDirectory from(NodeActivityStatusData.DirectoryFact fact) {
            return new NodeDirectory(fact.getName(), fact.getPath(), fact.getType(), fact.getLibrary(),
                    fact.getTotalBytes() == null ? null : fact.getTotalBytes().doubleValue(),
                    fact.getFreeBytes() == null ? null : fact.getFreeBytes().doubleValue());
        }
    }

    public record HelperDisk(String name, List<String> jobs) {
        static HelperDisk from(NodeActivityStatusData.HelperDiskFact fact) {
            return new HelperDisk(fact.getName(), fact.getJobs() == null ? List.of() : fact.getJobs());
        }
    }

    public record QueueStat(String queue, int depth, int consumers) {
        public static QueueStat from(QueueStatsStatusData.QueueStat stat) {
            return new QueueStat(stat.getQueue(), stat.getDepth(), stat.getConsumers());
        }
    }

    public record EventFailure(String nodeName, String queue, String eventType, String errorMessage, String occurredAt) {
        public static EventFailure from(EventFailureStatusData data) {
            return new EventFailure(data.getNodeName(), data.getQueue(), data.getEventType(),
                    data.getErrorMessage(), String.valueOf(data.getTimestamp()));
        }
    }

    public record TranscodePass(String nodeName, String mediaFileId, String title, String quality,
                                boolean background, String startedAt, String context, String contextType,
                                String contextId) {
        public static TranscodePass from(String nodeName, TranscodeActivityStatusData.TranscodePass pass) {
            return new TranscodePass(nodeName, pass.getMediaFileId(), pass.getTitle(), pass.getQuality(),
                    pass.isBackground(), String.valueOf(pass.getStartedAt()), pass.getContext(),
                    pass.getContextType(), pass.getContextId());
        }
    }

    public static ServerActivityEvent from(NodeActivityStatusData data) {
        return new ServerActivityEvent(ServerActivityEventType.NODE_ACTIVITY, data.getNodeName(),
                String.valueOf(data.getTimestamp()),
                data.getProcessing().stream().map(ProcessingItem::from).toList(),
                data.getProcessedCount(), data.getFailedCount(), null, null, null, NodeInfo.from(data.getFacts()));
    }

    public static ServerActivityEvent from(QueueStatsStatusData data) {
        return new ServerActivityEvent(ServerActivityEventType.QUEUE_STATS, data.getNodeName(),
                String.valueOf(data.getTimestamp()), null, null, null,
                data.getStats().stream().map(QueueStat::from).toList(), null, null, null);
    }

    public static ServerActivityEvent from(EventFailureStatusData data) {
        return new ServerActivityEvent(ServerActivityEventType.FAILURE, data.getNodeName(),
                String.valueOf(data.getTimestamp()), null, null, null, null, EventFailure.from(data), null, null);
    }

    public static ServerActivityEvent from(TranscodeActivityStatusData data) {
        return new ServerActivityEvent(ServerActivityEventType.TRANSCODE_ACTIVITY, data.getNodeName(),
                String.valueOf(data.getTimestamp()), null, null, null, null, null,
                data.getPasses().stream().map(pass -> TranscodePass.from(data.getNodeName(), pass)).toList(), null);
    }
}
