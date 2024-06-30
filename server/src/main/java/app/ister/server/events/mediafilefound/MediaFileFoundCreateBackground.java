package app.ister.server.events.mediafilefound;

import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class MediaFileFoundCreateBackground {
    public void createBackground(Path toPath, Path mediaFilePath, String dirOfFFmpeg, long atDurationInMilliseconds) {
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
