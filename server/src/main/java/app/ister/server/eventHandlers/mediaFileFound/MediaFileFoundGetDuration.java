package app.ister.server.eventHandlers.mediaFileFound;

import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.NullOutput;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;

import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

public class MediaFileFoundGetDuration {
    public static long getDuration(String path, String dirOfFFmpeg) {
        final AtomicLong durationMillis = new AtomicLong();

        FFmpeg.atPath(Paths.get(dirOfFFmpeg))
                .addInput(
                        UrlInput.fromUrl(path)
                )
                .addOutput(new NullOutput())
                .setProgressListener(progress -> durationMillis.set(progress.getTimeMillis()))
                .execute();
        return durationMillis.get();
    }
}
