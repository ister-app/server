package app.ister.server;

import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.enums.StreamCodecType;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.FFmpegProgress;
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

    private final String dirOfFFmpeg;

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

    public void start(String filePath, String toDir, int startTimeInSeconds, int audioIndex, Optional<MediaFileStreamEntity> subtitleMediaFileStream, ProgressListener progressListener) {
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
                .addArguments("-ac", "2")
                .addArguments("-hls_time", "6")
                .addArgument("-copyts")
                .addArguments("-hls_flags", "temp_file")
                .addArguments("-hls_segment_type", "mpegts")
                .addArguments("-hls_playlist_type", "event");

        //for i in *.mkv; do ./ffmpeg -i "$i" -preset ultrafast -map 0:v -map 0:1 -c:v:0 libx264 -c:a aac -ar 48000 -b:a 128k -ac 2 "${i%.*}.ts"; done
//        /usr/bin/ffmpeg -loglevel level+error -ss 0 -i "/mnt2/The Big Bang Theory (2007)/Season 07/s07e09.mkv" -y -f hls -preset ultrafast -map 0:v -map 0:1 -c:v:0 libx264 -c:a aac -ar 48000 -b:a 128k -ac 2 -hls_time 6 -copyts -hls_segment_type mpegts -hls_playlist_type event /tmp/6ca0b778-7bf3-4bf1-a873-b2aa36cadeec/index.m3u8
// yt-dlp --hls-prefer-native "" --write-subs
        // ./ffmpeg -y -ss 60 -i s07e01.mkv -preset ultrafast -copyts -map 0:v -map 0:1 -c:v:0 libx264 -c:a aac -ar 48000 -b:a 128k -ac 2 -vsync passthrough -avoid_negative_ts make_non_negative -max_muxing_queue_size 2048 -f hls -start_number 10 -movflags frag_custom+dash+delay_moov+frag_discont -hls_flags temp_file -max_delay 5000000 -hls_time 6 -force_key_frames "expr:gte(t,n_forced*6)" -hls_segment_type mpegts -hls_playlist_type event -hls_segment_filename ./test/%d.m4s ./test/playlist.m3u8

        if (subtitleMediaFileStream.isPresent()) {
            String[] subtileArguments = getSubtitleArgument(subtitleMediaFileStream.get(), startTimeInSeconds);
            outputWithArguments
                    .addArguments(subtileArguments[0], subtileArguments[1]);
        }


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
        ProcessHandle.current().children().forEach(System.out::println);
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
                FFmpeg.atPath(Path.of(dirOfFFmpeg))
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
}
