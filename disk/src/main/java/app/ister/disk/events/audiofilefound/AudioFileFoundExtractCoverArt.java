package app.ister.disk.events.audiofilefound;

import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class AudioFileFoundExtractCoverArt {
    public void extract(Path outputPath, String mediaFilePath, String dirOfFFmpeg) throws IOException {
        Files.createDirectories(outputPath.getParent());
        FFmpeg.atPath(Path.of(dirOfFFmpeg))
                .addInput(UrlInput.fromUrl(mediaFilePath))
                .addOutput(UrlOutput.toPath(outputPath)
                        .addArguments("-map", "0:v:0")
                        .addArguments("-frames:v", "1"))
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
    }
}
