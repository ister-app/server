package app.ister.server.transcoder;

import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.enums.StreamCodecType;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;

import java.nio.file.Path;
import java.util.Optional;

public class UrlOutputCreator {
    public static UrlOutput getUrlOutput(String toDir, int startTimeInSeconds, int audioIndex, Optional<MediaFileStreamEntity> subtitleMediaFileStream, String dirOfFFmpeg) {
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
            String[] subtileArguments = getSubtitleArgument(toDir, subtitleMediaFileStream.get(), startTimeInSeconds, dirOfFFmpeg);
            outputWithArguments
                    .addArguments(subtileArguments[0], subtileArguments[1]);
        }
        return outputWithArguments;
    }

    private static String[] getSubtitleArgument(String toDir, MediaFileStreamEntity subtitleMediaFileStream, int startTimeInSeconds, String dirOfFFmpeg) {
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

    private static String pathStringEscapeSpecialChars(String path) {
        return path.replaceAll("'", "\\\\\\\\\\\\\'");
    }
}
