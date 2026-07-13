package app.ister.transcoder.events;

import app.ister.core.EventHandlingException;
import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.transcoder.HlsService;
import app.ister.transcoder.PassFilter;
import app.ister.transcoder.HlsTranscodeService;
import app.ister.transcoder.config.TranscoderQueueNamingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
@RequiredArgsConstructor
public class HandleTranscodeRequested implements Handle<TranscodeRequestedData> {

    private final HlsService hlsService;
    private final HlsTranscodeService transcodeService;
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
    public void handle(TranscodeRequestedData data) {
        log.debug("Handling TRANSCODE_REQUESTED for mediaFileId={}", data.getMediaFileId());
        try {
            hlsService.generateAllPlaylists(data.getMediaFileId(), data.getDirect(), data.getTranscode(), data.getSubtitleFormat());
            if (data.getKeepUntilEpochMillis() != null) {
                transcodeService.extendKeepUntil(data.getMediaFileId(), data.getKeepUntilEpochMillis());
            }
            if (Boolean.TRUE.equals(data.getPreTranscode())) {
                if (transcodeService.hasAnyActiveOrCompletedPassForFile(data.getMediaFileId())) {
                    log.debug("Skipping pre-transcode passes for {} - already transcoding or recently completed", data.getMediaFileId());
                } else {
                    hlsService.startAllPasses(data.getMediaFileId(), data.getDirect(), data.getTranscode(),
                            PassFilter.preTranscode(data.getAudioLanguages(), data.getMaxVideoHeight()));
                }
            }
        } catch (IOException e) {
            throw new EventHandlingException("Failed to handle TRANSCODE_REQUESTED for mediaFileId=" + data.getMediaFileId(), e);
        }
    }
}
