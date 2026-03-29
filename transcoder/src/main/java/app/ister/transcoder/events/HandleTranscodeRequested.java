package app.ister.transcoder.events;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.transcoder.HlsService;
import app.ister.transcoder.config.TranscoderQueueNamingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class HandleTranscodeRequested implements Handle<TranscodeRequestedData> {

    private final HlsService hlsService;
    private final TranscoderQueueNamingConfig transcoderQueueNamingConfig;

    @Override
    public EventType handles() {
        return EventType.TRANSCODE_REQUESTED;
    }

    @RabbitListener(queues = "#{@transcoderQueueNamingConfig.getTranscodeRequestedQueues()}")
    @Override
    public void listener(TranscodeRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public Boolean handle(TranscodeRequestedData data) {
        log.debug("Handling TRANSCODE_REQUESTED for mediaFileId={}", data.getMediaFileId());
        try {
            hlsService.generateAllPlaylists(data.getMediaFileId(), data.getDirect(), data.getTranscode(), data.getSubtitleFormat());
        } catch (Exception e) {
            log.error("Failed to handle TRANSCODE_REQUESTED for mediaFileId={}", data.getMediaFileId(), e);
            return false;
        }
        return true;
    }
}
