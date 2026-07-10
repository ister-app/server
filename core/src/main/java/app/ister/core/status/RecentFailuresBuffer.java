package app.ister.core.status;

import app.ister.core.eventdata.EventFailureStatusData;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Recent dead-lettered events from all nodes, newest first. In-memory by design:
 * the dead-letter queue remains the durable record; this only feeds the live
 * activity view. Volumes are tiny, so plain synchronization suffices.
 */
@Component
public class RecentFailuresBuffer {

    static final int CAPACITY = 100;

    private final Deque<EventFailureStatusData> failures = new ArrayDeque<>();

    public synchronized void add(EventFailureStatusData failure) {
        failures.addFirst(failure);
        while (failures.size() > CAPACITY) {
            failures.removeLast();
        }
    }

    public synchronized List<EventFailureStatusData> snapshot() {
        return List.copyOf(failures);
    }
}
