package app.ister.core.status;

import app.ister.core.eventdata.TranscodeActivityStatusData;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last known transcode passes per node, fed by StatusEventListener. Unlike
 * NodeActivityRegistry this one IS swept: TranscodeActivityPublisher keepalives every
 * 30s while passes run, so an entry that has not been refreshed for {@link #EXPIRY}
 * belongs to a dead node and its passes must not linger on the activity screen.
 * (There are no counters here, so dropping the whole entry loses nothing.)
 */
@Component
public class TranscodeActivityRegistry {

    static final Duration EXPIRY = Duration.ofSeconds(90);

    private final Map<String, TranscodeActivityStatusData> nodes = new ConcurrentHashMap<>();

    public void update(TranscodeActivityStatusData data) {
        if (data.getPasses() == null || data.getPasses().isEmpty()) {
            // The publisher's final empty publication on the busy->idle transition.
            nodes.remove(data.getNodeName());
        } else {
            nodes.put(data.getNodeName(), data);
        }
    }

    /** Last known transcode snapshot per node, for the initial-state query. */
    public List<TranscodeActivityStatusData> nodesSnapshot() {
        return nodes.values().stream()
                .sorted(Comparator.comparing(TranscodeActivityStatusData::getNodeName))
                .toList();
    }

    @Scheduled(fixedDelay = 30_000)
    public void sweep() {
        Instant cutoff = Instant.now().minus(EXPIRY);
        nodes.values().removeIf(node -> node.getTimestamp().isBefore(cutoff));
    }
}
