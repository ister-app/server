package app.ister.disk.events.detectsegments;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.DetectSegmentsData;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Season-wide intro/outro detection, one chunk of episodes per message.
 *
 * <p>Fingerprinting a whole season in one go can outlast RabbitMQ's consumer timeout (default
 * 30 minutes), which closes the channel and requeues the message forever. So each message detects
 * at most {@code app.ister.server.segment-detect.chunk-size} episodes and, when pending episodes
 * remain, publishes a successor message for the same season. Not annotated {@code @Transactional}:
 * the successor must only be published after the chunk's transaction has committed, see
 * {@link SegmentDetectionChunkProcessor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandleDetectSegments implements Handle<DetectSegmentsData> {

    private final SegmentDetectionChunkProcessor chunkProcessor;
    private final MessageSender messageSender;

    @Value("${app.ister.server.segment-detect.chunk-size:4}")
    private int chunkSize;

    @Override
    public EventType handles() {
        return EventType.DETECT_SEGMENTS;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getDetectSegmentsQueues()}")
    @Override
    public void listener(DetectSegmentsData detectSegmentsData) {
        Handle.super.listener(detectSegmentsData);
    }

    @Override
    public void handle(DetectSegmentsData data) {
        SegmentDetectionChunkProcessor.Chunk chunk =
                chunkProcessor.process(data.getSeasonEntityUUID(), data.getDirectoryEntityUUID(), chunkSize);
        if (chunk.remaining() == 0) {
            return;
        }
        if (chunk.directoryName() == null) {
            log.warn("Directory {} of season {} is gone, not continuing segment detection",
                    data.getDirectoryEntityUUID(), data.getSeasonEntityUUID());
            return;
        }
        log.debug("Segment detection chunk of {} done for season {}, {} episode(s) remaining",
                chunk.processed(), data.getSeasonEntityUUID(), chunk.remaining());
        messageSender.sendDetectSegments(DetectSegmentsData.builder()
                        .eventType(EventType.DETECT_SEGMENTS)
                        .seasonEntityUUID(data.getSeasonEntityUUID())
                        .directoryEntityUUID(data.getDirectoryEntityUUID())
                        .build(),
                chunk.directoryName());
    }
}
