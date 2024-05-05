package app.ister.server.transcoder;

import app.ister.server.entitiy.TranscodeSessionEntity;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Optional;

@Slf4j
public class ProcessHelper {
    public static void pauseTranscodeProcess(TranscodeSessionEntity transcodeSessionEntity) {
        getProcess(transcodeSessionEntity).ifPresent(pid -> {
            try {
                new ProcessBuilder("/usr/bin/kill", "-19", Long.toString(pid)).start();
                transcodeSessionEntity.getPaused().set(true);
                log.debug("Pausing transcoding for transcodeSessionEntity: {}", transcodeSessionEntity.getId());
            } catch (IOException e) {
                log.error("Failed pausing transcodeSessionEntity: {}, pid: {}", transcodeSessionEntity.getId(), pid, e);
            }
        });
    }

    public static void continueTranscodeProcess(TranscodeSessionEntity transcodeSessionEntity) {
        getProcess(transcodeSessionEntity).ifPresent(pid -> {
            try {
                new ProcessBuilder("/usr/bin/kill", "-18", Long.toString(pid)).start();
                transcodeSessionEntity.getPaused().set(false);
                log.debug("Resuming transcoding for transcodeSessionEntity: {}", transcodeSessionEntity.getId());
            } catch (IOException e) {
                log.error("Failed continuing transcodeSessionEntity: {}, pid: {}", transcodeSessionEntity.getId(), pid, e);
            }
        });
    }

    private static Optional<Long> getProcess(TranscodeSessionEntity transcodeSessionEntity) {
        for(ProcessHandle processHandle: ProcessHandle.current().children().toList()) {
            if (processHandle.info().commandLine().orElse("").contains(transcodeSessionEntity.getDir())) {
                log.debug("Process found for transcodeSessionEntity: {}, pid: {}", transcodeSessionEntity.getId(), processHandle.pid());
                return Optional.of(processHandle.pid());
            }
        }
        return Optional.empty();
    }
}
