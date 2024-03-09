package app.ister.server.eventHandlers.mediaFileFound;

import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.enums.ImageType;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;

import java.nio.file.Path;

public class MediaFileFoundCreateBackground {
    public static ImageEntity createBackground(DiskEntity diskEntity, EpisodeEntity episodeEntity, String toPath, String mediaFile, String dirOfFFmpeg) {
        FFmpeg.atPath(Path.of(dirOfFFmpeg))
                .addInput(
                        UrlInput.fromUrl(mediaFile)
                )
                .addOutput(
                        UrlOutput.toPath(Path.of(toPath))
                                .addArguments("-ss", "00:01:00")
                                .addArguments("-vf", "scale='trunc(ih*dar):ih',setsar=1/1")
                                .addArguments("-frames:v", "1")
                                .addArguments("-q:v", "2")
                )
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
        return ImageEntity.builder()
                .diskEntity(diskEntity)
                .path(toPath)
                .type(ImageType.BACKGROUND)
                .episodeEntity(episodeEntity)
                .build();
    }
}
