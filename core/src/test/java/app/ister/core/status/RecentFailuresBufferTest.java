package app.ister.core.status;

import app.ister.core.eventdata.EventFailureStatusData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentFailuresBufferTest {

    private static EventFailureStatusData failure(int sequence) {
        return EventFailureStatusData.builder()
                .nodeName("node")
                .queue("queue-" + sequence)
                .timestamp(Instant.EPOCH.plusSeconds(sequence))
                .build();
    }

    @Test
    void newestFailureComesFirst() {
        RecentFailuresBuffer buffer = new RecentFailuresBuffer();
        buffer.add(failure(1));
        buffer.add(failure(2));

        List<EventFailureStatusData> snapshot = buffer.snapshot();

        assertEquals("queue-2", snapshot.getFirst().getQueue());
        assertEquals("queue-1", snapshot.getLast().getQueue());
    }

    @Test
    void oldestFailuresAreDroppedBeyondCapacity() {
        RecentFailuresBuffer buffer = new RecentFailuresBuffer();
        IntStream.rangeClosed(1, RecentFailuresBuffer.CAPACITY + 5).forEach(i -> buffer.add(failure(i)));

        List<EventFailureStatusData> snapshot = buffer.snapshot();

        assertEquals(RecentFailuresBuffer.CAPACITY, snapshot.size());
        assertEquals("queue-" + (RecentFailuresBuffer.CAPACITY + 5), snapshot.getFirst().getQueue());
        assertEquals("queue-6", snapshot.getLast().getQueue());
    }
}
