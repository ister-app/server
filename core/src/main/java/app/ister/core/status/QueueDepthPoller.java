package app.ister.core.status;

import app.ister.core.eventdata.QueueStatsStatusData;
import app.ister.core.eventdata.QueueStatsStatusData.QueueStat;
import app.ister.core.service.MessageSender;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Polls the depth of every queue this node has declared, via AMQP passive declares
 * (AmqpAdmin.getQueueInfo) — no RabbitMQ management API needed. Publishes on the status
 * exchange only when something changed. Nodes only poll their own queues; the
 * QueueStatsRegistry merges the cluster view by queue name.
 */
@Component
public class QueueDepthPoller {

    private static final String QUEUE_PREFIX = "app.ister.server.";

    private final AmqpAdmin amqpAdmin;
    private final MessageSender messageSender;
    private final String nodeName;
    private final List<String> queueNames;

    private List<QueueStat> lastStats;

    public QueueDepthPoller(AmqpAdmin amqpAdmin, MessageSender messageSender,
                            @Value("${app.ister.server.name}") String nodeName,
                            Collection<Queue> queues, Collection<Declarables> declarables) {
        this.amqpAdmin = amqpAdmin;
        this.messageSender = messageSender;
        this.nodeName = nodeName;
        this.queueNames = collectQueueNames(queues, declarables);
    }

    /**
     * All app queues known to this node. The prefix filter keeps the anonymous
     * per-node status queues (spring.gen-*) out of the overview.
     */
    private static List<String> collectQueueNames(Collection<Queue> queues, Collection<Declarables> declarables) {
        TreeSet<String> names = new TreeSet<>();
        queues.forEach(queue -> names.add(queue.getName()));
        for (Declarables declarable : declarables) {
            for (Declarable item : declarable.getDeclarables()) {
                if (item instanceof Queue queue) {
                    names.add(queue.getName());
                }
            }
        }
        names.removeIf(name -> !name.startsWith(QUEUE_PREFIX));
        return List.copyOf(names);
    }

    @Scheduled(fixedDelay = 5000)
    public void pollAndPublishIfChanged() {
        List<QueueStat> stats = queueNames.stream()
                .map(this::poll)
                .filter(Objects::nonNull)
                .toList();
        if (stats.equals(lastStats)) {
            return;
        }
        messageSender.sendStatus(new QueueStatsStatusData(nodeName, Instant.now(), stats));
        lastStats = stats;
    }

    private QueueStat poll(String queueName) {
        QueueInformation info = amqpAdmin.getQueueInfo(queueName);
        if (info == null) {
            return null;
        }
        return new QueueStat(queueName, (int) info.getMessageCount(), (int) info.getConsumerCount());
    }
}
