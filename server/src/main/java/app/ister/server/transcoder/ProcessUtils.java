package app.ister.server.transcoder;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Optional;

@Slf4j
public class ProcessUtils {

    private ProcessUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static void pauseTranscodeProcess(TranscodeSessionData transcodeSessionData) {
        getProcess(transcodeSessionData).ifPresent(pid -> {
            try {
                new ProcessBuilder("/usr/bin/kill", "-19", Long.toString(pid)).start();
                transcodeSessionData.getPaused().set(true);
                log.debug("Pausing transcoding for transcodeSessionEntity: {}", transcodeSessionData.getId());
            } catch (IOException e) {
                log.error("Failed pausing transcodeSessionEntity: {}, pid: {}", transcodeSessionData.getId(), pid, e);
            }
        });
    }

    public static void continueTranscodeProcess(TranscodeSessionData transcodeSessionData) {
        getProcess(transcodeSessionData).ifPresent(pid -> {
            try {
                new ProcessBuilder("/usr/bin/kill", "-18", Long.toString(pid)).start();
                transcodeSessionData.getPaused().set(false);
                log.debug("Resuming transcoding for transcodeSessionEntity: {}", transcodeSessionData.getId());
            } catch (IOException e) {
                log.error("Failed continuing transcodeSessionEntity: {}, pid: {}", transcodeSessionData.getId(), pid, e);
            }
        });
    }

    private static Optional<Long> getProcess(TranscodeSessionData transcodeSessionData) {
        for (ProcessHandle processHandle : ProcessHandle.current().children().toList()) {
            if (processHandle.info().commandLine().orElse("").contains(transcodeSessionData.getDir())) {
                log.debug("Process found for transcodeSessionEntity: {}, pid: {}", transcodeSessionData.getId(), processHandle.pid());
                return Optional.of(processHandle.pid());
            }
        }
        return Optional.empty();
    }
}
