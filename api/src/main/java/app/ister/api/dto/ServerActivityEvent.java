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
        List<TranscodePass> transcodes) {

    public enum ServerActivityEventType {NODE_ACTIVITY, QUEUE_STATS, FAILURE, TRANSCODE_ACTIVITY}

    public record ProcessingItem(String queue, String eventType, String startedAt, String subject, String step) {
        static ProcessingItem from(NodeActivityStatusData.ProcessingItem item) {
            return new ProcessingItem(item.getQueue(), item.getEventType(), String.valueOf(item.getStartedAt()),
                    item.getSubject(), item.getStep());
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
                                boolean background, String startedAt) {
        public static TranscodePass from(String nodeName, TranscodeActivityStatusData.TranscodePass pass) {
            return new TranscodePass(nodeName, pass.getMediaFileId(), pass.getTitle(), pass.getQuality(),
                    pass.isBackground(), String.valueOf(pass.getStartedAt()));
        }
    }

    public static ServerActivityEvent from(NodeActivityStatusData data) {
        return new ServerActivityEvent(ServerActivityEventType.NODE_ACTIVITY, data.getNodeName(),
                String.valueOf(data.getTimestamp()),
                data.getProcessing().stream().map(ProcessingItem::from).toList(),
                data.getProcessedCount(), data.getFailedCount(), null, null, null);
    }

    public static ServerActivityEvent from(QueueStatsStatusData data) {
        return new ServerActivityEvent(ServerActivityEventType.QUEUE_STATS, data.getNodeName(),
                String.valueOf(data.getTimestamp()), null, null, null,
                data.getStats().stream().map(QueueStat::from).toList(), null, null);
    }

    public static ServerActivityEvent from(EventFailureStatusData data) {
        return new ServerActivityEvent(ServerActivityEventType.FAILURE, data.getNodeName(),
                String.valueOf(data.getTimestamp()), null, null, null, null, EventFailure.from(data), null);
    }

    public static ServerActivityEvent from(TranscodeActivityStatusData data) {
        return new ServerActivityEvent(ServerActivityEventType.TRANSCODE_ACTIVITY, data.getNodeName(),
                String.valueOf(data.getTimestamp()), null, null, null, null, null,
                data.getPasses().stream().map(pass -> TranscodePass.from(data.getNodeName(), pass)).toList());
    }
}
