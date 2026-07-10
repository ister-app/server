package app.ister.core.eventdata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Queue depths as seen by one node (AMQP passive declares of the queues it has
 * declared itself), published on the status fan-out exchange.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueStatsStatusData {
    private String nodeName;
    private Instant timestamp;
    private List<QueueStat> stats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueueStat {
        private String queue;
        private int depth;
        private int consumers;
    }
}
