package app.ister.disk.events.subtitleextract;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.SubtitleExtractRequestedData;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

/**
 * One embedded subtitle stream → one SRT in the owner's cache directory. Directory-scoped and
 * helper-capable: a node listing the directory under {@code app.ister.helper.disks} consumes the
 * same queue and does the OCR for the owner. Not {@code @Transactional}: the extraction runs for
 * minutes and must not hold a database session, see {@link SubtitleExtractionProcessor}.
 */
@Service
@RequiredArgsConstructor
public class HandleSubtitleExtractRequested implements Handle<SubtitleExtractRequestedData> {

    private final SubtitleExtractionProcessor processor;

    @Override
    public EventType handles() {
        return EventType.SUBTITLE_EXTRACT_REQUESTED;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getSubtitleExtractRequestedQueues()}",
            concurrency = "${app.ister.helper.concurrency}")
    @Override
    public void listener(SubtitleExtractRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(SubtitleExtractRequestedData data) {
        processor.process(data.getMediaFileEntityUUID(), data.getSubtitleStreamEntityUUID());
    }
}
