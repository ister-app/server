package app.ister.transcoder;

import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.node.MediaFileInputResolver;
import app.ister.core.utils.Jaffree;
import app.ister.transcoder.bitmapsub.BitmapSubtitle;
import app.ister.transcoder.bitmapsub.PgsParser;
import app.ister.transcoder.bitmapsub.SpriteSheetWriter;
import app.ister.transcoder.bitmapsub.VobSubParser;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Turns bitmap subtitle streams (Blu-ray PGS, DVD VobSub) into sprite sheets
 * plus a JSON cue index that the player draws over the video itself — the
 * pictures the disc shipped, not an OCR guess at their text.
 *
 * <p>Nothing is decoded by ffmpeg: it only copies the subtitle packets out
 * (seconds, even for a 30 GB file, and one run for all streams), and
 * {@link PgsParser}/{@link VobSubParser} do the rest. That is cheap enough to
 * happen when playback is set up, so the artifacts live in the transcode tmp
 * dir next to the WebVTT segments rather than in the database and the cache.</p>
 */
@Service
@Slf4j
public class HlsBitmapSubtitleService {

    /** Bump when the parsers or the index format change: older artifacts are then regenerated. */
    static final int BITMAP_GENERATION = 1;

    static final String FILE_PREFIX = "bsub_";

    private static final Set<String> PGS_CODECS = Set.of("hdmv_pgs_subtitle", "pgssub");
    private static final Set<String> VOBSUB_CODECS = Set.of("dvd_subtitle", "dvdsub");
    private static final long MKVEXTRACT_TIMEOUT_SECONDS = 120;

    private final Jaffree jaffree;
    private final String mkvextractPath;

    @Autowired
    public HlsBitmapSubtitleService(Jaffree jaffree,
                                    @Value("${app.ister.server.mkvextract:/usr/bin/mkvextract}") String mkvextractPath) {
        this.jaffree = jaffree;
        this.mkvextractPath = mkvextractPath;
    }

    /** Whether this service can serve the stream. DVB bitmaps are not covered. */
    public static boolean isSupported(MediaFileStreamEntity stream) {
        if (stream.getCodecType() != StreamCodecType.SUBTITLE || stream.getCodecName() == null) {
            return false;
        }
        String codec = stream.getCodecName().toLowerCase(Locale.ROOT);
        return PGS_CODECS.contains(codec) || VOBSUB_CODECS.contains(codec);
    }

    static String baseName(UUID streamId) {
        return FILE_PREFIX + streamId;
    }

    /** The stream id inside {@code bsub_<uuid>.json} / {@code bsub_<uuid>_NN.png} / {@code bsub_<uuid>.gen}. */
    static UUID streamIdOf(String fileName) {
        if (!fileName.startsWith(FILE_PREFIX) || fileName.length() < FILE_PREFIX.length() + 36) {
            throw new IllegalArgumentException("Not a bitmap subtitle file name: " + fileName);
        }
        return UUID.fromString(fileName.substring(FILE_PREFIX.length(), FILE_PREFIX.length() + 36));
    }

    static Path generationMarker(Path cacheDir, UUID streamId) {
        return cacheDir.resolve(baseName(streamId) + ".gen");
    }

    boolean isGenerationCurrent(Path cacheDir, UUID streamId) {
        try {
            Path marker = generationMarker(cacheDir, streamId);
            return Files.exists(marker)
                    && String.valueOf(BITMAP_GENERATION).equals(Files.readString(marker).trim());
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * Writes index + sheets + generation marker for every given stream into
     * {@code cacheDir} and returns the written files, each stream's marker
     * after its other files. One ffmpeg run covers all streams: the cost is
     * reading the container once, not per stream. A stream that cannot be
     * parsed gets an empty index rather than failing its siblings.
     */
    List<Path> generate(String inputPath, List<MediaFileStreamEntity> streams, Path cacheDir) throws IOException {
        List<MediaFileStreamEntity> supported = streams.stream().filter(HlsBitmapSubtitleService::isSupported).toList();
        if (supported.isEmpty()) {
            return List.of();
        }
        Files.createDirectories(cacheDir);
        long started = System.nanoTime();
        List<Path> scratch = new ArrayList<>();
        try {
            FFmpeg ffmpeg = jaffree.getFFMPEG()
                    .addInput(MediaFileInputResolver.ffmpegInput(inputPath))
                    .setOverwriteOutput(true)
                    .setLogLevel(LogLevel.ERROR);
            for (MediaFileStreamEntity stream : supported) {
                Path raw = rawFile(cacheDir, stream);
                scratch.add(raw);
                ffmpeg.addOutput(UrlOutput.toPath(raw)
                        .addArguments("-map", "0:" + stream.getStreamIndex())
                        .addArguments("-c:s", "copy")
                        .addArguments("-f", isPgs(stream) ? "sup" : "matroska"));
            }
            ffmpeg.execute();

            List<Path> written = new ArrayList<>();
            for (MediaFileStreamEntity stream : supported) {
                BitmapSubtitle subtitle = parse(stream, cacheDir, scratch);
                written.addAll(SpriteSheetWriter.write(subtitle, cacheDir, baseName(stream.getId())));
                Path marker = generationMarker(cacheDir, stream.getId());
                Files.writeString(marker, String.valueOf(BITMAP_GENERATION), StandardCharsets.UTF_8);
                written.add(marker);
                log.debug("Bitmap subtitle stream {} ({}): {} cues", stream.getStreamIndex(), stream.getCodecName(),
                        subtitle.cues().size());
            }
            log.info("Generated bitmap subtitles for {} stream(s) in {} ms", supported.size(),
                    (System.nanoTime() - started) / 1_000_000);
            return written;
        } finally {
            for (Path file : scratch) {
                Files.deleteIfExists(file);
            }
        }
    }

    private BitmapSubtitle parse(MediaFileStreamEntity stream, Path cacheDir, List<Path> scratch) {
        try {
            Path raw = rawFile(cacheDir, stream);
            if (isPgs(stream)) {
                return PgsParser.parse(raw);
            }
            // mkvextract names its output after the base it is given: <base>.idx + <base>.sub
            Path base = cacheDir.resolve(baseName(stream.getId()) + "_vobsub");
            Path idx = Path.of(base + ".idx");
            Path sub = Path.of(base + ".sub");
            scratch.add(idx);
            scratch.add(sub);
            runMkvextract(raw, base);
            return VobSubParser.parse(idx, sub);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not parse bitmap subtitle stream {} ({}): {}", stream.getStreamIndex(),
                    stream.getCodecName(), e.toString());
            return new BitmapSubtitle(0, 0, List.of());
        }
    }

    private void runMkvextract(Path mks, Path base) throws IOException {
        Process process = new ProcessBuilder(mkvextractPath, mks.toString(), "tracks", "0:" + base)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(MKVEXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("mkvextract timed out");
            }
        } catch (InterruptedException _) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for mkvextract");
        }
        // mkvextract exits 1 on warnings, which still leaves usable output
        if (process.exitValue() > 1) {
            throw new IOException("mkvextract failed with exit code " + process.exitValue());
        }
    }

    private static boolean isPgs(MediaFileStreamEntity stream) {
        return PGS_CODECS.contains(stream.getCodecName().toLowerCase(Locale.ROOT));
    }

    private static Path rawFile(Path cacheDir, MediaFileStreamEntity stream) {
        return cacheDir.resolve(baseName(stream.getId()) + (isPgs(stream) ? "_raw.sup" : "_raw.mks"));
    }
}
