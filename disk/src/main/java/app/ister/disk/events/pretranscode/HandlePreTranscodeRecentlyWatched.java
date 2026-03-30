package app.ister.disk.events.pretranscode;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.eventdata.PreTranscodeRecentlyWatchedData;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.core.service.MessageSender;
import app.ister.core.service.PreTranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class HandlePreTranscodeRecentlyWatched implements Handle<PreTranscodeRecentlyWatchedData> {

    private final PreTranscodeService preTranscodeService;
    private final MessageSender messageSender;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @Override
    public EventType handles() {
        return EventType.PRE_TRANSCODE_RECENTLY_WATCHED;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getPreTranscodeRecentlyWatchedQueues()}")
    @Override
    public void listener(PreTranscodeRecentlyWatchedData data) {
        Handle.super.listener(data);
    }

    @Override
    public Boolean handle(PreTranscodeRecentlyWatchedData data) {
        String diskName = data.getDiskName();
        log.info("Handling PRE_TRANSCODE_RECENTLY_WATCHED for disk: {}", diskName);

        Set<UUID> mediaFileIds = preTranscodeService.collectMediaFileIdsToPreTranscode(diskName);

        updateKeepFile(diskName, mediaFileIds);

        mediaFileIds.forEach(mediaFileId ->
                messageSender.sendTranscodeRequested(
                        TranscodeRequestedData.builder()
                                .eventType(EventType.TRANSCODE_REQUESTED)
                                .mediaFileId(mediaFileId)
                                .direct(false)
                                .transcode(true)
                                .subtitleFormat(SubtitleFormat.WEBVTT)
                                .preTranscode(true)
                                .build(),
                        diskName)
        );

        log.info("Queued {} media files for pre-transcoding on disk: {}", mediaFileIds.size(), diskName);
        return true;
    }

    private void updateKeepFile(String diskName, Set<UUID> mediaFileIds) {
        Path keepFile = Path.of(tmpDir, "pretranscode_keep_" + diskName + ".txt");
        try {
            Files.createDirectories(keepFile.getParent());
            String content = mediaFileIds.stream()
                    .map(UUID::toString)
                    .collect(Collectors.joining("\n"));
            Files.writeString(keepFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Updated keep file {} with {} entries", keepFile, mediaFileIds.size());
        } catch (IOException e) {
            log.warn("Could not update keep file {}", keepFile, e);
        }
    }
}
