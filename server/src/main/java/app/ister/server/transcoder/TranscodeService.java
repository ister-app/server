package app.ister.server.transcoder;

import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.enums.StreamCodecType;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import app.ister.server.repository.PlayQueueRepository;
import com.github.kokorin.jaffree.ffmpeg.ProgressListener;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class TranscodeService {
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;
    @Autowired
    private PlayQueueRepository playQueueRepository;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;
    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    private final ArrayList<TranscodeSessionData> transcodeSessionEntities = new ArrayList<>();

    /**
     * Every 5 seconds check all the transcode sessions.
     * Calculate based on the play queue time the buffered time.
     * If the buffered time is more then 120 seconds, pause the transcoding.
     * If the buffered time is less then 50 seconds, continue the transcoding.
     */
    @Scheduled(cron = "*/5 * * * * *")
    @Transactional
    public void checkTranscodeSessions() {
        transcodeSessionEntities.forEach(transcodeSessionData -> playQueueRepository.findById(transcodeSessionData.getPlayQueueId()).ifPresent(playQueue -> {
            long progressOfTranscodingInSeconds = (transcodeSessionData.getProgressTimeInMilliseconds().get() / 1000) + transcodeSessionData.getStartTimeInSeconds() - (playQueue.getProgressInMilliseconds() / 1000);
            log.debug("Check transcode sessions: {}, item: {}, progress: {}, stopped: {}, paused: {}", transcodeSessionData.getId(), playQueue.getCurrentItem(), progressOfTranscodingInSeconds, transcodeSessionData.getStopped().get(), transcodeSessionData.getPaused().get());
            if (!transcodeSessionData.getStopped().get() && !transcodeSessionData.getPaused().get() && playQueue.getProgressInMilliseconds() != 0 && progressOfTranscodingInSeconds > 120) {
                ProcessUtils.pauseTranscodeProcess(transcodeSessionData);
            } else if(!transcodeSessionData.getStopped().get() && transcodeSessionData.getPaused().get() && progressOfTranscodingInSeconds < 60) {
                ProcessUtils.continueTranscodeProcess(transcodeSessionData);
            }
        }));
    }

    @PreDestroy
    public void clearMovieCache() {
        log.debug("Shutting down transcode sessions");
        transcodeSessionEntities.forEach(transcodeSession -> {
            stopTranscoding(transcodeSession.getId());
        });
    }

    /**
     * Check if the transcode session is ready.
     * It's ready if the first file is created.
     */
    public boolean readyTranscoding(UUID id) {
        log.debug("Ready check: {}", id);
        var result = false;
        if (!transcodeSessionEntities.isEmpty()) {
            result = getSesion(id).orElseThrow().getTranscoder().ready();
        }
        return result;
    }

    /**
     * Stop a transcode session.
     */
    public boolean stopTranscoding(UUID id) {
        log.debug("Shutting down transcode sessions: {}", id);
        TranscodeSessionData transcodeSessionData = getSesion(id).orElseThrow();
        transcodeSessionData.getStopped().set(true);
        ProcessUtils.continueTranscodeProcess(transcodeSessionData);
        transcodeSessionData.getTranscoder().stop();
        transcodeSessionEntities.remove(transcodeSessionData);
        return true;
    }

    /**
     * Start a transcode session.
     */
    public UUID startTranscoding(UUID playQueueId, UUID mediaFileId, int startTimeInSeconds, Optional<UUID> audioId, Optional<UUID> subtitleId) throws IOException {
        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();

        // Set the optional subtitleMediaFileStream.
        Optional<MediaFileStreamEntity> subtitleMediaFileStream = Optional.empty();
        if (subtitleId.isPresent()) {
            subtitleMediaFileStream = mediaFileStreamRepository.findById(subtitleId.get());
        }

        // Set the audio index
        Integer audioIndex = null;
        if (audioId.isPresent()) {
            var audioMediaFileStream = mediaFileStreamRepository.findById(audioId.get());
            if (audioMediaFileStream.isPresent()) {
                audioIndex = audioMediaFileStream.get().getStreamIndex();
            }
        }
        if (audioId.isEmpty() || audioIndex == null) {
            var firstAudioStream = mediaFile.getMediaFileStreamEntity().stream().filter(mediaFileStream -> mediaFileStream.getCodecType().equals(StreamCodecType.AUDIO)).findFirst();
            if (firstAudioStream.isPresent()) {
                audioIndex = firstAudioStream.get().getStreamIndex();
            }
        }
        TranscodeSessionData transcodeSession = createSession(playQueueId, mediaFile, startTimeInSeconds);
        log.debug("Starting: {} for mediafile: {}", transcodeSession.getId(), mediaFileId);
        transcodeSession.getTranscoder().start(mediaFile.getPath(), transcodeSession.getDir(), startTimeInSeconds, audioIndex, subtitleMediaFileStream, onProgress(transcodeSession));
        return transcodeSession.getId();
    }

    private ProgressListener onProgress(TranscodeSessionData transcodeSession) {
        return progress -> {
            log.debug("Transcoding mediafile: {}, time: {}", transcodeSession.getDir(), progress.getTime(TimeUnit.SECONDS));
            if (progress.getTime(TimeUnit.MILLISECONDS) != null) {
                transcodeSession.getProgressTimeInMilliseconds().set(progress.getTime(TimeUnit.MILLISECONDS));
            }
        };
    }

    private TranscodeSessionData createSession(UUID playQueueId, MediaFileEntity mediaFile, int startTimeInSeconds) throws IOException {
        UUID id = UUID.randomUUID();
        TranscodeSessionData transcodeSessionData = TranscodeSessionData.builder()
                .transcoder(new Transcoder(dirOfFFmpeg))
                .playQueueId(playQueueId)
                .mediaFile(mediaFile)
                .startTimeInSeconds(startTimeInSeconds)
                .id(id)
                .progressTimeInMilliseconds(new AtomicLong(0))
                .pid(new AtomicLong())
                .stopped(new AtomicBoolean(false))
                .paused(new AtomicBoolean(false))
                .dir(tmpDir + id + "/").build();
        Files.createDirectories(Paths.get(tmpDir + transcodeSessionData.getId()));
        transcodeSessionEntities.add(transcodeSessionData);
        return transcodeSessionData;
    }

    private Optional<TranscodeSessionData> getSesion(UUID id) {
        return transcodeSessionEntities.stream().filter(transcodeSessionData -> transcodeSessionData.getId().equals(id)).findFirst();
    }

}
