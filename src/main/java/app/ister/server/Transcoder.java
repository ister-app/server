package app.ister.server;

import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class Transcoder {
    private FFmpegResultFuture async;

    private String toDir;

    private String dirOfFFmpeg;

    public Transcoder(String dirOfFFmpeg) {
        this.dirOfFFmpeg = dirOfFFmpeg;
    }


    @PreDestroy
    public void clearMovieCache() {
        stop();
        System.out.println("shutting down!!!");
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
                if (path.toString().endsWith("vtt") || path.toString().endsWith("ts") || path.toString().endsWith("m3u8")) {
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

    public void start(String filePath, String toDir, int startTimeInSeconds, int audioIndex, Optional<Integer> subtitleIndex) {
        this.toDir = toDir;

        UrlOutput outputWithArguments = UrlOutput.toPath(Path.of(toDir).resolve("index.m3u8"))
                .setFormat("hls")
                .addArguments("-preset", "ultrafast")
                .addArguments("-map", "0:v")
                .addArguments("-map", "0:" + audioIndex)
                .addArguments("-c:v:0", "libx264")
                .addArguments("-c:a", "aac")
                .addArguments("-ar", "48000")
                .addArguments("-b:a", "128k")
                .addArguments("-ac", "2");

        //                                .addArguments("-c:s:3", "dvd_subtitle")
        //                                .addArguments("-vf" , "subtitles='" + "s01e01.mkv"+"'")
        subtitleIndex.ifPresent(integer -> outputWithArguments.addArguments("-filter_complex", "[0:v][0:" + integer + "]overlay"));

        outputWithArguments
                .addArguments("-ss", String.valueOf(startTimeInSeconds))
                .addArguments("-hls_time", "6")
                .addArguments("-hls_segment_type", "mpegts")
                .addArguments("-hls_playlist_type", "event")
                .addArguments("-threads", "4");


        async = FFmpeg.atPath(Path.of(dirOfFFmpeg))
                .addInput(
                        UrlInput.fromUrl(filePath)
                )
                .addOutput(
                        outputWithArguments
                )
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .executeAsync();
    }
}
