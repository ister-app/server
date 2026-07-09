package app.ister.disk.events.pretranscode;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.eventdata.PreTranscodeRecentlyWatchedData;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.core.service.MessageSender;
import app.ister.core.service.PreTranscodeService;
import app.ister.core.service.PreTranscodeService.UnanalyzedMediaFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class HandlePreTranscodeRecentlyWatched implements Handle<PreTranscodeRecentlyWatchedData> {

    private final PreTranscodeService preTranscodeService;
    private final MessageSender messageSender;

    /** Media files for which re-analysis was already requested; avoids re-sending every scheduler run. */
    private final Set<UUID> analyzeRequested = ConcurrentHashMap.newKeySet();

    /**
     * Retention window sent with each pre-transcode request. The scheduler runs every 15
     * minutes, so as long as a media file keeps matching the recently-watched rules its
     * deadline keeps sliding forward; once the rule stops matching the cache expires.
     */
    @Value("${app.ister.server.pretranscode.keep-minutes:30}")
    private long keepMinutes;

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
    public void handle(PreTranscodeRecentlyWatchedData data) {
        String diskName = data.getDiskName();
        log.info("Handling PRE_TRANSCODE_RECENTLY_WATCHED for disk: {}", diskName);

        PreTranscodeService.PreTranscodeCollection collection = preTranscodeService.collectMediaFilesToPreTranscode(diskName);
        Set<UUID> mediaFileIds = collection.mediaFileIds();
        long keepUntilEpochMillis = System.currentTimeMillis() + Duration.ofMinutes(keepMinutes).toMillis();

        mediaFileIds.forEach(mediaFileId ->
                messageSender.sendTranscodeRequested(
                        TranscodeRequestedData.builder()
                                .eventType(EventType.TRANSCODE_REQUESTED)
                                .mediaFileId(mediaFileId)
                                .direct(false)
                                .transcode(true)
                                .subtitleFormat(SubtitleFormat.WEBVTT)
                                .preTranscode(true)
                                .keepUntilEpochMillis(keepUntilEpochMillis)
                                .build(),
                        diskName)
        );

        requestAnalysisForUnanalyzed(diskName, collection.unanalyzedFiles());

        log.info("Queued {} media files for pre-transcoding on disk: {}", mediaFileIds.size(), diskName);
    }

    /**
     * A media file without analyzed streams cannot be transcoded (its master playlist would be
     * empty). Request a re-analysis via MEDIA_FILE_FOUND instead — once per file per application
     * run — so the file self-heals and is picked up by a later pre-transcode cycle.
     */
    private void requestAnalysisForUnanalyzed(String diskName, Set<UnanalyzedMediaFile> unanalyzedFiles) {
        unanalyzedFiles.forEach(file -> {
            if (analyzeRequested.add(file.mediaFileId())) {
                log.warn("Media file {} ({}) has no analyzed streams; requesting re-analysis instead of pre-transcode",
                        file.mediaFileId(), file.path());
                messageSender.sendMediaFileFound(
                        MediaFileFoundData.builder()
                                .eventType(EventType.MEDIA_FILE_FOUND)
                                .directoryEntityUUID(file.directoryId())
                                .episodeEntityUUID(file.episodeId())
                                .movieEntityUUID(file.movieId())
                                .path(file.path())
                                .build(),
                        diskName);
            }
        });
    }

}
