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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingItem {
        private String queue;
        private String eventType;
        private Instant startedAt;
    }
}
