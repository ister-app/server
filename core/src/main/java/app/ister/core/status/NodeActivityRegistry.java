package app.ister.core.status;

import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.NodeActivityStatusData.ProcessingItem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks the work this node is processing right now (fed by ProcessingActivityAdvice)
 * and the last activity snapshot of every node in the cluster (fed by
 * StatusEventListener from the status fan-out exchange).
 */
@Component
public class NodeActivityRegistry {

    private final AtomicLong tokenSequence = new AtomicLong();
    private final Map<Long, ProcessingItem> inFlight = new ConcurrentHashMap<>();
    private final LongAdder processedCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();

    private final Map<String, NodeActivityStatusData> nodes = new ConcurrentHashMap<>();

    /** Registers work starting on this node; returns a token for {@link #finished}. */
    public long started(String queue, String eventType, Instant startedAt) {
        long token = tokenSequence.incrementAndGet();
        inFlight.put(token, new ProcessingItem(queue, eventType, startedAt));
        return token;
    }

    public void finished(long token, boolean failed) {
        if (inFlight.remove(token) != null) {
            (failed ? failedCount : processedCount).increment();
        }
    }

    /** Snapshot of this node's own activity, for publication on the status exchange. */
    public NodeActivityStatusData localSnapshot(String nodeName, Instant now) {
        List<ProcessingItem> processing = inFlight.values().stream()
                .sorted(Comparator.comparing(ProcessingItem::getStartedAt))
                .toList();
        return new NodeActivityStatusData(nodeName, now, processing, processedCount.sum(), failedCount.sum());
    }

    /** Stores the latest snapshot received from any node (including this one). */
    public void updateNode(NodeActivityStatusData data) {
        nodes.put(data.getNodeName(), data);
    }

    /** Last known activity snapshot per node, for the initial-state query. */
    public List<NodeActivityStatusData> nodesSnapshot() {
        return nodes.values().stream()
                .sorted(Comparator.comparing(NodeActivityStatusData::getNodeName))
                .toList();
    }
}
