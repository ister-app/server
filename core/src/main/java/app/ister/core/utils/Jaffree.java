package app.ister.core.utils;

import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;

@Service
public class Jaffree {
    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    public FFmpeg getFFMPEG() {
        return FFmpeg.atPath(Paths.get(dirOfFFmpeg));
    }

    public FFprobe getFFPROBE() {
        return FFprobe.atPath(Paths.get(dirOfFFmpeg));
    }
}
