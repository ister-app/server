package app.ister.core.status;

import app.ister.core.eventdata.DeviceCommandData;
import app.ister.core.eventdata.DevicePresenceData;
import app.ister.core.eventdata.EventFailureStatusData;
import app.ister.core.eventdata.FollowerStatusData;
import app.ister.core.eventdata.NodeActivityStatusData;
import app.ister.core.eventdata.PlaybackCommandData;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.eventdata.QueueStatsStatusData;
import app.ister.core.eventdata.TranscodeActivityStatusData;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Component;

/**
 * Consumes the status fan-out exchange (this node's anonymous queue) and feeds the
 * in-memory registries + websocket broadcaster. Every node — including the publisher
 * itself — runs this, so the cluster state converges on all nodes. Handlers stay
 * trivial: no database access (RabbitMQ listener threads have no Hibernate session).
 * The queue name is resolved via SpEL; Queue.getName() is registered for native
 * reflection in core's reflect-config.json.
 */
@Component
// Jackson binding of the @RabbitHandler payloads in the GraalVM native image.
@RegisterReflectionForBinding({NodeActivityStatusData.class, QueueStatsStatusData.class,
        EventFailureStatusData.class, PlaybackStatusData.class, PlaybackCommandData.class,
        FollowerStatusData.class, DeviceCommandData.class, DevicePresenceData.class,
        TranscodeActivityStatusData.class})
@RabbitListener(queues = "#{statusQueue.name}")
public class StatusEventListener {

    private final NodeActivityRegistry nodeActivityRegistry;
    private final TranscodeActivityRegistry transcodeActivityRegistry;
    private final QueueStatsRegistry queueStatsRegistry;
    private final RecentFailuresBuffer recentFailuresBuffer;
    private final PlaybackSessionRegistry playbackSessionRegistry;
    private final FollowerRegistry followerRegistry;
    private final DevicePresenceRegistry devicePresenceRegistry;
    private final ServerStatusBroadcaster broadcaster;

    public StatusEventListener(NodeActivityRegistry nodeActivityRegistry,
                               TranscodeActivityRegistry transcodeActivityRegistry, QueueStatsRegistry queueStatsRegistry,
                               RecentFailuresBuffer recentFailuresBuffer, PlaybackSessionRegistry playbackSessionRegistry,
                               FollowerRegistry followerRegistry, DevicePresenceRegistry devicePresenceRegistry,
                               ServerStatusBroadcaster broadcaster) {
        this.nodeActivityRegistry = nodeActivityRegistry;
        this.transcodeActivityRegistry = transcodeActivityRegistry;
        this.queueStatsRegistry = queueStatsRegistry;
        this.recentFailuresBuffer = recentFailuresBuffer;
        this.playbackSessionRegistry = playbackSessionRegistry;
        this.followerRegistry = followerRegistry;
        this.devicePresenceRegistry = devicePresenceRegistry;
        this.broadcaster = broadcaster;
    }

    @RabbitHandler
    public void onNodeActivity(NodeActivityStatusData data) {
        nodeActivityRegistry.updateNode(data);
        broadcaster.emitActivity(data);
    }

    @RabbitHandler
    public void onTranscodeActivity(TranscodeActivityStatusData data) {
        transcodeActivityRegistry.update(data);
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

    @RabbitHandler
    public void onPlaybackCommand(PlaybackCommandData data) {
        broadcaster.emitCommand(data);
    }

    @RabbitHandler
    public void onDeviceCommand(DeviceCommandData data) {
        broadcaster.emitDeviceCommand(data);
    }

    @RabbitHandler
    public void onDevicePresence(DevicePresenceData data) {
        // Registry-only: device online state is pulled via the myDevices query, not broadcast.
        devicePresenceRegistry.update(data);
    }

    @RabbitHandler
    public void onFollower(FollowerStatusData data) {
        followerRegistry.update(data);
        // The follower count rides on the now-playing sessions; re-emit so cards refresh live.
        broadcaster.emitNowPlaying(playbackSessionRegistry.snapshot());
    }
}
