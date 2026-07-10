package app.ister.core.status;

import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.service.MessageSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Publishes this node's activity snapshot on the status exchange, throttled to one
 * message per 2s and only when something changed — per-delivery publishing would double
 * broker traffic during scan bursts.
 */
@Component
public class NodeActivityPublisher {

    private final NodeActivityRegistry registry;
    private final MessageSender messageSender;
    private final String nodeName;

    private List<NodeActivityStatusData.ProcessingItem> lastProcessing;
    private long lastProcessedCount = -1;
    private long lastFailedCount = -1;

    public NodeActivityPublisher(NodeActivityRegistry registry, MessageSender messageSender,
                                 @Value("${app.ister.server.name}") String nodeName) {
        this.registry = registry;
        this.messageSender = messageSender;
        this.nodeName = nodeName;
    }

    @Scheduled(fixedDelay = 2000)
    public void publishIfChanged() {
        NodeActivityStatusData snapshot = registry.localSnapshot(nodeName, Instant.now());
        if (Objects.equals(snapshot.getProcessing(), lastProcessing)
                && snapshot.getProcessedCount() == lastProcessedCount
                && snapshot.getFailedCount() == lastFailedCount) {
            return;
        }
        messageSender.sendStatus(snapshot);
        lastProcessing = snapshot.getProcessing();
        lastProcessedCount = snapshot.getProcessedCount();
        lastFailedCount = snapshot.getFailedCount();
    }
}
