package app.ister.core.utils;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/**
 * Where background work finds its ffmpeg/ffprobe: a directory with wrapper scripts that start the
 * real binaries under {@code nice -n <background-nice>}, so that analysis (stream probing, crop and
 * intro detection, subtitle extraction, stills) and background transcodes only get the CPU cycles
 * that playback and uploads leave unused. Jaffree resolves {@code <dir>/ffmpeg} and
 * {@code <dir>/ffprobe}, so the scripts simply shadow the binaries' names.
 *
 * <p>{@code nice} is probed once at startup; when it is missing or disabled ({@code background-nice}
 * 0) everything runs at normal priority from the real ffmpeg directory.
 */
@Slf4j
@Service
public class BackgroundFfmpeg {

    /** Name of the wrapper directory inside tmp-dir; the cache cleanup skips it by this name. */
    public static final String WRAPPER_DIR_NAME = "ffmpeg-nice";

    @Value("${app.ister.server.background-nice:10}")
    private int backgroundNice;

    /** Absolute, so the wrapper never depends on PATH. */
    @Value("${app.ister.server.nice-path:/usr/bin/nice}")
    private String nicePath;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String ffmpegDir;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    private Path wrapperDir;

    @PostConstruct
    void init() {
        wrapperDir = createWrappers();
    }

    /** The wrapper directory, or null when background work runs at normal priority. */
    public Path wrapperDir() {
        return wrapperDir;
    }

    /** The directory to hand to {@code FFmpeg.atPath}/{@code FFprobe.atPath} for background work. */
    public String dir() {
        return wrapperDir == null ? ffmpegDir : wrapperDir.toString();
    }

    /**
     * Writes the {@code ffmpeg} and {@code ffprobe} wrapper scripts, owner-only, in the app-owned
     * tmp dir (not the world-writable system temp dir). Any failure disables the wrappers: the
     * work still runs, just at normal priority.
     *
     * @return the wrapper directory, or null when disabled or unavailable
     */
    Path createWrappers() {
        if (backgroundNice <= 0) {
            return null;
        }
        try {
            Process probe = new ProcessBuilder(nicePath, "-n", String.valueOf(backgroundNice), "true")
                    .redirectErrorStream(true)
                    .start();
            if (!probe.waitFor(5, TimeUnit.SECONDS) || probe.exitValue() != 0) {
                probe.destroyForcibly();
                log.warn("'{} -n {}' probe failed; background ffmpeg/ffprobe run at normal priority", nicePath, backgroundNice);
                return null;
            }
            Path dir = Paths.get(tmpDir, WRAPPER_DIR_NAME);
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            for (String binary : new String[]{"ffmpeg", "ffprobe"}) {
                Path script = dir.resolve(binary);
                String real = Paths.get(ffmpegDir).resolve(binary).toString();
                Files.writeString(script, "#!/bin/sh\nexec " + nicePath + " -n " + backgroundNice + " " + real + " \"$@\"\n");
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
            }
            log.info("Background ffmpeg/ffprobe run with nice -n {} via {}", backgroundNice, dir);
            return dir;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while probing 'nice'; background ffmpeg/ffprobe run at normal priority");
            return null;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not set up nice wrappers; background ffmpeg/ffprobe run at normal priority", e);
            return null;
        }
    }
}
