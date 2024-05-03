package app.ister.server.service;

import app.ister.server.Transcoder;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.entitiy.PlayQueueEntity;
import app.ister.server.entitiy.TranscodeSessionEntity;
import app.ister.server.enums.StreamCodecType;
import app.ister.server.eventHandlers.Handle;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import app.ister.server.repository.PlayQueueRepository;
import com.github.kokorin.jaffree.ffmpeg.ProgressListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.data.method.annotation.Argument;
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

    @Scheduled(cron = "*/5 * * * * *")
    @Transactional
    protected void checkTranscodeSessions() {
        transcodeSessionEntities.forEach(transcodeSessionEntity -> {
            playQueueRepository.findById(transcodeSessionEntity.getPlayQueueId()).ifPresent(playQueue -> {

                /**
                 * Check if should be paused
                 * progressTranscode 180 seconds
                 * startime 60 seconds
                 * progress watching 100 seconds
                 * is transconding 140 seconds future so pause
                 */
                long progressOfTranscodingInSeconds = (transcodeSessionEntity.getProgressTimeInMilliseconds().get() / 1000) + transcodeSessionEntity.getStartTimeInSeconds() - (playQueue.getProgressInMilliseconds() / 1000);
                log.debug("Check transcode sessions: {}, item: {}, progress: {}, stopped: {}, paused: {}", transcodeSessionEntity.getId(), playQueue.getCurrentItem(), progressOfTranscodingInSeconds, transcodeSessionEntity.getStopped().get(), transcodeSessionEntity.getPaused().get());
                if (!transcodeSessionEntity.getStopped().get() && !transcodeSessionEntity.getPaused().get() && playQueue.getProgressInMilliseconds() != 0 && progressOfTranscodingInSeconds > 120) {
                    pauseTranscodeProcess(transcodeSessionEntity);
                } else if(!transcodeSessionEntity.getStopped().get() && transcodeSessionEntity.getPaused().get() && progressOfTranscodingInSeconds < 60) {
                    resumeTranscodeProcess(transcodeSessionEntity);
                }
            });
        });
    }

    private static void pauseTranscodeProcess(TranscodeSessionEntity transcodeSessionEntity) {
        getProcess(transcodeSessionEntity).ifPresent(pid -> {
            try {
                new ProcessBuilder("kill", "-19", Long.toString(pid)).start();
                transcodeSessionEntity.getPaused().set(true);
                log.debug("Pausing transcoding for transcodeSessionEntity: {}", transcodeSessionEntity.getId());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void resumeTranscodeProcess(TranscodeSessionEntity transcodeSessionEntity) {
        getProcess(transcodeSessionEntity).ifPresent(pid -> {
            try {
                new ProcessBuilder("kill", "-18", Long.toString(pid)).start();
                transcodeSessionEntity.getPaused().set(false);
                log.debug("Resuming transcoding for transcodeSessionEntity: {}", transcodeSessionEntity.getId());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static Optional<Long> getProcess(TranscodeSessionEntity transcodeSessionEntity) {
        for(ProcessHandle processHandle: ProcessHandle.current().children().toList()) {
            if (processHandle.info().commandLine().orElse("").contains(transcodeSessionEntity.getDir())) {
                log.debug("Process found: {}", processHandle.pid());
                return Optional.of(processHandle.pid());
            }
        }
        return Optional.empty();
    }

    public boolean readyTranscoding(UUID id) {
        log.debug("Ready check: {}", id);
        var result = false;
        if (!transcodeSessionEntities.isEmpty()) {
            result = getSesion(id).orElseThrow().getTranscoder().ready();
        }
        return result;
    }

    public boolean stopTranscoding(UUID id) {
        log.debug("Stopping: {}", id);
        TranscodeSessionEntity transcodeSessionEntity = getSesion(id).orElseThrow();
        transcodeSessionEntity.getStopped().set(true);
        resumeTranscodeProcess(transcodeSessionEntity);
        transcodeSessionEntity.getTranscoder().stop();
        transcodeSessionEntities.remove(transcodeSessionEntity);
        return true;
    }

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
