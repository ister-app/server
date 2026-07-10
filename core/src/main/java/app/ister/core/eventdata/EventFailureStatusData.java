package app.ister.core.eventdata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Published on the status fan-out exchange when a message exhausts its retries and is
 * dead-lettered (see RabbitReliabilityConfig). Live visibility only — the dead-letter
 * queue remains the durable record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventFailureStatusData {
    private String nodeName;
    private Instant timestamp;
    private String queue;
    private String eventType;
    private String errorMessage;
}
