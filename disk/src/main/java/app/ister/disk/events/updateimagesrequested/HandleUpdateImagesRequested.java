package app.ister.disk.events.updateimagesrequested;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.UpdateImagesRequestedData;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fills in missing blur-hashes for one directory, one chunk per message.
 *
 * <p>When a chunk comes back full there is more work, so a successor message is published carrying a
 * cursor past the chunk just handled. An empty (or short) chunk ends the chain. Not annotated
 * {@code @Transactional}: see {@link BlurHashChunkProcessor}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HandleUpdateImagesRequested implements Handle<UpdateImagesRequestedData> {

    private final BlurHashChunkProcessor chunkProcessor;
    private final MessageSender messageSender;

    @Value("${app.ister.server.blur-hash.chunk-size:500}")
    private int chunkSize;

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getUpdateImagesRequestedQueues()}")
    @Override
    public void listener(UpdateImagesRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public EventType handles() {
        return EventType.UPDATE_IMAGES_REQUESTED;
    }

    @Override
    public void handle(UpdateImagesRequestedData data) {
        BlurHashChunkProcessor.Chunk chunk =
                chunkProcessor.process(data.getDirectoryEntityId(), data.getAfterId(), chunkSize);

        if (chunk.size() < chunkSize) {
            log.info("Blur-hash sweep finished for directory {}", data.getDirectoryName());
            return;
        }

        log.debug("Blur-hash chunk of {} done for directory {}, continuing after {}",
                chunk.size(), data.getDirectoryName(), chunk.lastId());
        messageSender.sendUpdateImagesRequested(
                UpdateImagesRequestedData.builder()
                        .eventType(EventType.UPDATE_IMAGES_REQUESTED)
                        .directoryEntityId(data.getDirectoryEntityId())
                        .directoryName(data.getDirectoryName())
                        .afterId(chunk.lastId())
                        .build(),
                data.getDirectoryName());
    }
}
