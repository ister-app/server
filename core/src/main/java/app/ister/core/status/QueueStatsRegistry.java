package app.ister.core.status;

import app.ister.core.eventdata.QueueStatsStatusData;
import app.ister.core.eventdata.QueueStatsStatusData.QueueStat;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cluster-wide queue depths, keyed by queue name. Shared (un-suffixed) queues are
 * reported by every node; last write wins, which is harmless because every node
 * observes the same broker state.
 */
@Component
public class QueueStatsRegistry {

    private final Map<String, QueueStat> stats = new ConcurrentHashMap<>();

    public void update(QueueStatsStatusData data) {
        data.getStats().forEach(stat -> stats.put(stat.getQueue(), stat));
    }

    public List<QueueStat> snapshot() {
        return stats.values().stream()
                .sorted(Comparator.comparing(QueueStat::getQueue))
                .toList();
    }
}
