package app.ister.core.status;

import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.eventdata.QueueStatsStatusData;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the status fan-out exchange (this node's anonymous queue) and feeds the
 * in-memory registries + websocket broadcaster. Every node — including the publisher
 * itself — runs this, so the cluster state converges on all nodes. Handlers stay
 * trivial: no database access (RabbitMQ listener threads have no Hibernate session).
 */
@Component
@RabbitListener(queues = "#{statusQueue.name}")
public class StatusEventListener {

    private final NodeActivityRegistry nodeActivityRegistry;
    private final QueueStatsRegistry queueStatsRegistry;
    private final RecentFailuresBuffer recentFailuresBuffer;
    private final PlaybackSessionRegistry playbackSessionRegistry;
    private final ServerStatusBroadcaster broadcaster;

    public StatusEventListener(NodeActivityRegistry nodeActivityRegistry, QueueStatsRegistry queueStatsRegistry,
                               RecentFailuresBuffer recentFailuresBuffer, PlaybackSessionRegistry playbackSessionRegistry,
                               ServerStatusBroadcaster broadcaster) {
        this.nodeActivityRegistry = nodeActivityRegistry;
        this.queueStatsRegistry = queueStatsRegistry;
        this.recentFailuresBuffer = recentFailuresBuffer;
        this.playbackSessionRegistry = playbackSessionRegistry;
        this.broadcaster = broadcaster;
    }

    @RabbitHandler
    public void onNodeActivity(NodeActivityStatusData data) {
        nodeActivityRegistry.updateNode(data);
        broadcaster.emitActivity(data);
    }

    @RabbitHandler
    public void onQueueStats(QueueStatsStatusData data) {
        queueStatsRegistry.update(data);
        broadcaster.emitActivity(data);
    }

    @RabbitHandler
    public void onFailure(EventFailureStatusData data) {
        recentFailuresBuffer.add(data);
        broadcaster.emitActivity(data);
    }

    @RabbitHandler
    public void onPlayback(PlaybackStatusData data) {
        playbackSessionRegistry.update(data);
        broadcaster.emitNowPlaying(playbackSessionRegistry.snapshot());
    }
}
