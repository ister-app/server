package app.ister.transcoder;

import app.ister.core.entitiy.MediaFileStreamEntity;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class Transcoder {
    private final String dirOfFFmpeg;
    private FFmpegResultFuture async;
    private String toDir;

    public Transcoder(String dirOfFFmpeg) {
        this.dirOfFFmpeg = dirOfFFmpeg;
    }

    public void stop() {
        if (async != null) {
            async.graceStop();
            async = null;
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                log.error("Error during deleting files: ", e);
            }
        }
        try {
            Files.list(Path.of(toDir)).forEach(path -> {
                if (path.toString().endsWith("vtt") || path.toString().endsWith("ts") || path.toString().endsWith("m3u8") || path.toString().endsWith("srt")) {
                    log.debug("Deleting: " + path);
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        log.error("Error during deleting files: ", e);
                    }
                }
            });
            Files.deleteIfExists(Path.of(toDir));
        } catch (IOException e) {
            log.error("Error during deleting files: ", e);
        }
    }

    public boolean ready() {
        return Files.exists(Path.of(toDir + "index.m3u8"));

    }

    public void start(String filePath, String toDir, int startTimeInSeconds, int audioIndex, Optional<MediaFileStreamEntity> subtitleMediaFileStream, ProgressListener progressListener) {
        this.toDir = toDir;

        UrlOutput outputWithArguments = UrlOutputUtils.getUrlOutput(toDir, startTimeInSeconds, audioIndex, subtitleMediaFileStream, dirOfFFmpeg);

        async = FFmpeg.atPath(Path.of(dirOfFFmpeg))
                .addInput(
                        UrlInput.fromUrl(filePath)
                                .addArguments("-ss", String.valueOf(startTimeInSeconds))
                )
                .addOutput(
                        outputWithArguments
                )
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .setProgressListener(progressListener)
                .executeAsync();
    }
}
