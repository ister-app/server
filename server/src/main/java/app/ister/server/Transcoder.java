package app.ister.server;

import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.entitiy.TranscodeSessionEntity;
import app.ister.server.enums.StreamCodecType;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.FFmpegResultFuture;
import com.github.kokorin.jaffree.ffmpeg.ProgressListener;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class Transcoder {
    private FFmpegResultFuture async;

    private String toDir;

    private final FFmpeg ffmpeg;

    private TranscodeSessionEntity transcodeSessionEntity;

    public Transcoder(FFmpeg ffmpeg) {
        this.ffmpeg = ffmpeg;
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
                if (path.toString().endsWith("vtt") || path.toString().endsWith("ts") || path.toString().endsWith("m3u8") || path.toString().endsWith("srt") ) {
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

    public void start(String filePath, String toDir, int startTimeInSeconds, int audioIndex, Optional<MediaFileStreamEntity> subtitleMediaFileStream, ProgressListener progressListener, TranscodeSessionEntity transcodeSession) {
        this.toDir = toDir;
        this.transcodeSessionEntity = transcodeSession;

        UrlOutput outputWithArguments = UrlOutput.toPath(Path.of(toDir).resolve("index.m3u8"))
                .setFormat("hls")
                .addArguments("-preset", "ultrafast")
                .addArguments("-map", "0:v")
                .addArguments("-map", "0:" + audioIndex)
                .addArguments("-c:v:0", "libx264")
                .addArguments("-c:a", "aac")
                .addArguments("-ar", "48000")
                .addArguments("-b:a", "128k")
                .addArguments("-ac", "2")
                .addArguments("-hls_time", "6")
                .addArgument("-copyts")
                .addArguments("-hls_flags", "temp_file")
                .addArguments("-hls_segment_type", "mpegts")
                .addArguments("-hls_playlist_type", "event");

        if (subtitleMediaFileStream.isPresent()) {
            String[] subtileArguments = getSubtitleArgument(subtitleMediaFileStream.get(), startTimeInSeconds);
            outputWithArguments
                    .addArguments(subtileArguments[0], subtileArguments[1]);
        }


        async = ffmpeg
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

    private String[] getSubtitleArgument(MediaFileStreamEntity subtitleMediaFileStream, int startTimeInSeconds) {
        String[] result = new String[2];
        if (subtitleMediaFileStream.getCodecType().equals(StreamCodecType.SUBTITLE)) {
            result[0] = "-filter_complex";
            result[1] = "[0:v][0:" + subtitleMediaFileStream.getStreamIndex() + "]overlay";
        } else if (subtitleMediaFileStream.getCodecType().equals(StreamCodecType.EXTERNAL_SUBTITLE)) {
            if (startTimeInSeconds == 0) {
                result[0] = "-vf";
                result[1] = "subtitles=" + pathStringEscapeSpecialChars(subtitleMediaFileStream.getPath());
            } else {
                ffmpeg
                        .addInput(
                                UrlInput.fromUrl(subtitleMediaFileStream.getPath())
                        )
                        .addOutput(
                                UrlOutput.toPath(Path.of(toDir).resolve("external_subtitles.srt"))
                                        .addArguments("-ss", String.valueOf(startTimeInSeconds))
                        )
                        .setOverwriteOutput(true)
                        .setLogLevel(LogLevel.ERROR)
                        .execute();
                result[0] = "-vf";
                result[1] = "subtitles=" + pathStringEscapeSpecialChars(Path.of(toDir).resolve("external_subtitles.srt").toUri().getPath());
            }
        }
        return result;
    }

    private String pathStringEscapeSpecialChars(String path) {
        return path.replaceAll("'", "\\\\\\\\\\\\\'");
    }

    public void pauseTranscodeProcess() {
        getProcess().ifPresent(pid -> {
            try {
                new ProcessBuilder("/usr/bin/kill", "-19", Long.toString(pid)).start();
                transcodeSessionEntity.getPaused().set(true);
                log.debug("Pausing transcoding for transcodeSessionEntity: {}", transcodeSessionEntity.getId());
            } catch (IOException e) {
                log.error("Failed pausing transcodeSessionEntity: {}, pid: {}", transcodeSessionEntity.getId(), pid, e);
            }
        });
    }

    public void continueTranscodeProcess() {
        getProcess().ifPresent(pid -> {
            try {
                new ProcessBuilder("/usr/bin/kill", "-18", Long.toString(pid)).start();
                transcodeSessionEntity.getPaused().set(false);
                log.debug("Resuming transcoding for transcodeSessionEntity: {}", transcodeSessionEntity.getId());
            } catch (IOException e) {
                log.error("Failed continuing transcodeSessionEntity: {}, pid: {}", transcodeSessionEntity.getId(), pid, e);
            }
        });
    }

    private Optional<Long> getProcess() {
        for(ProcessHandle processHandle: ProcessHandle.current().children().toList()) {
            if (processHandle.info().commandLine().orElse("").contains(transcodeSessionEntity.getDir())) {
                log.debug("Process found for transcodeSessionEntity: {}, pid: {}", transcodeSessionEntity.getId(), processHandle.pid());
                return Optional.of(processHandle.pid());
            }
        }
        return Optional.empty();
    }
}
