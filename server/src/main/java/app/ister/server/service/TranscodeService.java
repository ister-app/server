package app.ister.server.service;

import app.ister.server.Transcoder;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.entitiy.TranscodeSessionEntity;
import app.ister.server.enums.StreamCodecType;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import app.ister.server.repository.PlayQueueRepository;
import app.ister.server.transcoder.ProcessHelper;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.ProgressListener;
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

    private final ArrayList<TranscodeSessionEntity> transcodeSessionEntities = new ArrayList<>();

    /**
     * Every 5 seconds check all the transcode sessions.
     * Calculate based on the play queue time the buffered time.
     * If the buffered time is more then 120 seconds, pause the transcoding.
     * If the buffered time is less then 50 seconds, continue the transcoding.
     */
    @Scheduled(cron = "*/5 * * * * *")
    @Transactional
    public void checkTranscodeSessions() {
        transcodeSessionEntities.forEach(transcodeSessionEntity -> playQueueRepository.findById(transcodeSessionEntity.getPlayQueueId()).ifPresent(playQueue -> {
            long progressOfTranscodingInSeconds = (transcodeSessionEntity.getProgressTimeInMilliseconds().get() / 1000) + transcodeSessionEntity.getStartTimeInSeconds() - (playQueue.getProgressInMilliseconds() / 1000);
            log.debug("Check transcode sessions: {}, item: {}, progress: {}, stopped: {}, paused: {}", transcodeSessionEntity.getId(), playQueue.getCurrentItem(), progressOfTranscodingInSeconds, transcodeSessionEntity.getStopped().get(), transcodeSessionEntity.getPaused().get());
            if (!transcodeSessionEntity.getStopped().get() && !transcodeSessionEntity.getPaused().get() && playQueue.getProgressInMilliseconds() != 0 && progressOfTranscodingInSeconds > 120) {
                ProcessHelper.pauseTranscodeProcess(transcodeSessionEntity);
            } else if(!transcodeSessionEntity.getStopped().get() && transcodeSessionEntity.getPaused().get() && progressOfTranscodingInSeconds < 60) {
                ProcessHelper.continueTranscodeProcess(transcodeSessionEntity);
            }
        }));
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
        log.debug("Stopping: {}", id);
        TranscodeSessionEntity transcodeSessionEntity = getSesion(id).orElseThrow();
        transcodeSessionEntity.getStopped().set(true);
        ProcessHelper.continueTranscodeProcess(transcodeSessionEntity);
        transcodeSessionEntity.getTranscoder().stop();
        transcodeSessionEntities.remove(transcodeSessionEntity);
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
        TranscodeSessionEntity transcodeSession = createSession(playQueueId, mediaFile, startTimeInSeconds);
        log.debug("Starting: {} for mediafile: {}", transcodeSession.getId(), mediaFileId);
        transcodeSession.getTranscoder().start(mediaFile.getPath(), transcodeSession.getDir(), startTimeInSeconds, audioIndex, subtitleMediaFileStream, onProgress(transcodeSession));
        return transcodeSession.getId();
    }

    private ProgressListener onProgress(TranscodeSessionEntity transcodeSession) {
        return progress -> {
            log.debug("Transcoding mediafile: {}, time: {}", transcodeSession.getDir(), progress.getTime(TimeUnit.SECONDS));
            if (progress.getTime(TimeUnit.MILLISECONDS) != null) {
                transcodeSession.getProgressTimeInMilliseconds().set(progress.getTime(TimeUnit.MILLISECONDS));
            }
        };
    }

    private TranscodeSessionEntity createSession(UUID playQueueId, MediaFileEntity mediaFile, int startTimeInSeconds) throws IOException {
        UUID id = UUID.randomUUID();
        TranscodeSessionEntity transcodeSessionEntity = TranscodeSessionEntity.builder()
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
        Files.createDirectories(Paths.get(tmpDir + transcodeSessionEntity.getId()));
        transcodeSessionEntities.add(transcodeSessionEntity);
        return transcodeSessionEntity;
    }

    private Optional<TranscodeSessionEntity> getSesion(UUID id) {
        return transcodeSessionEntities.stream().filter(transcodeSessionEntity -> transcodeSessionEntity.getId().equals(id)).findFirst();
    }

}
