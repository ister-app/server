package app.ister.server.eventHandlers.mediaFileFound;

import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;

import java.nio.file.Path;

public class MediaFileFoundCreateBackground {
    public static void createBackground(Path toPath, Path mediaFilePath, String dirOfFFmpeg, long atDurationInMilliseconds) {
        FFmpeg.atPath(Path.of(dirOfFFmpeg))
                .addInput(
                        UrlInput.fromPath(mediaFilePath)
                                .addArguments("-ss", atDurationInMilliseconds + "ms")
                )
                .addOutput(
                        UrlOutput.toPath(toPath)
                                .addArguments("-vf", "scale='trunc(ih*dar):ih',setsar=1/1")
                                .addArguments("-frames:v", "1")
                                .addArguments("-q:v", "2")
                )
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
    }
}
