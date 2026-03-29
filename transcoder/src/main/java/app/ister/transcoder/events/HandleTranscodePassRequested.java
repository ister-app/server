package app.ister.transcoder.events;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.TranscodePassRequestedData;
import app.ister.transcoder.HlsService;
import app.ister.transcoder.config.TranscoderQueueNamingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class HandleTranscodePassRequested implements Handle<TranscodePassRequestedData> {

    private final HlsService hlsService;
    private final TranscoderQueueNamingConfig transcoderQueueNamingConfig;

    @Override
    public EventType handles() {
        return EventType.TRANSCODE_PASS_REQUESTED;
    }

    @RabbitListener(queues = "#{@transcoderQueueNamingConfig.getTranscodePassRequestedQueues()}")
    @Override
    public void listener(TranscodePassRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public Boolean handle(TranscodePassRequestedData data) {
        log.debug("Handling TRANSCODE_PASS_REQUESTED: passKey={}", data.getPassKey());
        try {
            hlsService.startPass(data);
        } catch (Exception e) {
            log.error("Failed to start pass: passKey={}", data.getPassKey(), e);
            return false;
        }
        return true;
    }
}
