package app.ister.disk.events.mediafilefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class MediaFileFoundExtractSubtitles {

    private static final Set<String> TEXT_SUBTITLE_CODECS = Set.of(
            "subrip", "ass", "ssa", "mov_text", "webvtt", "text"
    );
    // Keep in sync with HlsPlaylistBuilder.IMAGE_SUBTITLE_CODECS (transcoder):
    // what the playlist drops is exactly what OCR must rescue.
    public static final Set<String> IMAGE_SUBTITLE_CODECS = Set.of(
            "dvd_subtitle", "dvdsub", "hdmv_pgs_subtitle", "pgssub", "dvb_subtitle"
    );

    @Value("${app.ister.server.subtile-ocr:/usr/bin/subtile-ocr}")
    private String subtileOcrPath;

    @Value("${app.ister.server.mkvextract:/usr/bin/mkvextract}")
    private String mkvextractPath;

    /**
     * OCR fallback language (ISO 639-3) for image subtitles whose stream has
     * no usable language tag — common on DVD rips. Blank disables the
     * fallback: such streams are then skipped with a warning.
     */
    @Value("${app.ister.server.subtitle-ocr-default-language:eng}")
    private String ocrDefaultLanguage;

    private final Map<String, String> langMap = new HashMap<>();

    @PostConstruct
    void loadLangMap() {
        try (var stream = getClass().getResourceAsStream("/iso-639-3.tab");
             var reader = new BufferedReader(new InputStreamReader(stream))) {
            var _ = reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 2 && !parts[1].isBlank()) {
                    langMap.put(parts[1], parts[0]); // Part2b → ISO 639-3
                }
            }
            log.debug("Loaded {} ISO 639 language code mappings", langMap.size());
        } catch (Exception e) {
            log.warn("Could not load iso-639-3.tab: {}", e.getMessage());
        }
    }

    public List<MediaFileStreamEntity> extractSubtitles(
            MediaFileEntity mediaFile,
            List<MediaFileStreamEntity> streams,
            DirectoryEntity cacheDir,
            String ffmpegDir) {
        List<MediaFileStreamEntity> result = new ArrayList<>();
        int subIdx = 0;
        for (MediaFileStreamEntity stream : streams) {
            if (stream.getCodecType() != StreamCodecType.SUBTITLE) {
                continue;
            }
            extractOne(mediaFile, streams, stream, subIdx, cacheDir, ffmpegDir).ifPresent(result::add);
            subIdx++;
        }
        return result;
    }

    private Optional<MediaFileStreamEntity> extractOne(
            MediaFileEntity mediaFile, List<MediaFileStreamEntity> streams, MediaFileStreamEntity stream,
            int subIdx, DirectoryEntity cacheDir, String ffmpegDir) {
        String lang = normalizeLanguage(stream.getLanguage());
        String srtFilename = mediaFile.getId() + "_" + stream.getStreamIndex() + "_" + lang + ".srt";
        Path srtPath = Paths.get(cacheDir.getPath(), srtFilename);

        String codecName = stream.getCodecName() != null ? stream.getCodecName().toLowerCase() : "";
        boolean extracted = false;

        if (Files.exists(srtPath)) {
            log.debug("SRT already extracted, skipping: {}", srtPath);
            extracted = true;
        } else if (TEXT_SUBTITLE_CODECS.contains(codecName)) {
            extracted = extractTextSubtitle(mediaFile.getPath(), subIdx, srtPath, ffmpegDir);
        } else if (IMAGE_SUBTITLE_CODECS.contains(codecName)) {
            String effectiveLang = ocrExtract(mediaFile, streams, stream, subIdx, srtPath, lang, ffmpegDir);
            if (effectiveLang != null) {
                extracted = true;
                lang = effectiveLang;
            }
        } else {
            log.debug("Skipping subtitle stream {} with unsupported codec: {}", stream.getStreamIndex(), codecName);
        }

        if (!extracted) {
            return Optional.empty();
        }
        return Optional.of(MediaFileStreamEntity.builder()
                .mediaFileEntity(mediaFile)
                .streamIndex(stream.getStreamIndex())
                .codecName("subtitle srt")
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .language(lang)
                .title(stream.getTitle())
                .path(srtPath.toString())
                .build());
    }

    /** OCRs an image subtitle; returns the language for the stream row, or null when not extracted. */
    private String ocrExtract(MediaFileEntity mediaFile, List<MediaFileStreamEntity> streams,
                              MediaFileStreamEntity stream, int subIdx, Path srtPath, String lang, String ffmpegDir) {
        String ocrLang = resolveOcrLanguage(lang, streams);
        if (ocrLang == null) {
            log.warn("No usable OCR language for {} stream {} — set app.ister.server.subtitle-ocr-default-language to enable OCR for untagged subtitles",
                    mediaFile.getPath(), stream.getStreamIndex());
            return null;
        }
        if (!extractImageSubtitle(mediaFile.getPath(), subIdx, srtPath, ocrLang, ffmpegDir)) {
            return null;
        }
        // An untagged stream keeps "und" in its filename, but the row gets the OCR
        // language so the player's language-preference matching can find it.
        return "und".equals(lang) ? ocrLang : lang;
    }

    private String normalizeLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "und";
        return langMap.getOrDefault(lang, lang);
    }

    /**
     * The language to feed subtile-ocr (it needs real tesseract traineddata, so
     * "und" always fails): the stream's own tag, else the language of the
     * file's first tagged audio stream (a DVD's subs are usually in the disc's
     * language), else the configured default, else null (skip).
     */
    String resolveOcrLanguage(String normalizedStreamLang, List<MediaFileStreamEntity> streams) {
        if (!"und".equals(normalizedStreamLang)) {
            return normalizedStreamLang;
        }
        var audioLang = streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.AUDIO)
                .map(s -> normalizeLanguage(s.getLanguage()))
                .filter(l -> !"und".equals(l))
                .findFirst();
        if (audioLang.isPresent()) {
            return audioLang.get();
        }
        return (ocrDefaultLanguage != null && !ocrDefaultLanguage.isBlank()) ? ocrDefaultLanguage : null;
    }

    private boolean extractTextSubtitle(String inputPath, int subIdx, Path srtPath, String ffmpegDir) {
        try {
            FFmpeg.atPath(Paths.get(ffmpegDir))
                    .addInput(UrlInput.fromUrl(inputPath))
                    .addArguments("-map", "0:s:" + subIdx)
                    .addArguments("-c:s", "srt")
                    .addOutput(UrlOutput.toPath(srtPath))
                    .setOverwriteOutput(true)
                    .setLogLevel(LogLevel.ERROR)
                    .execute();
            log.debug("Extracted text subtitle to {}", srtPath);
            return true;
        } catch (Exception e) {
            log.warn("Failed to extract text subtitle (stream 0:s:{}): {}", subIdx, e.getMessage());
            return false;
        }
    }

    private boolean extractImageSubtitle(String inputPath, int subIdx, Path srtPath, String lang, String ffmpegDir) {
        String srtStem = srtPath.getFileName().toString().replace(".srt", "");
        Path tmpDir = srtPath.getParent().resolve(".sub_tmp_" + srtStem);
        try {
            Files.createDirectories(tmpDir);
            Path mksPath = tmpDir.resolve(srtStem + ".mks");
            String subBase = tmpDir.resolve(srtStem).toString();
            Path idxPath = tmpDir.resolve(srtStem + ".idx");

            // Step 1: ffmpeg → dvdsub MKS
            FFmpeg.atPath(Paths.get(ffmpegDir))
                    .addInput(UrlInput.fromUrl(inputPath))
                    .addArguments("-map", "0:s:" + subIdx)
                    .addArguments("-c:s", "dvdsub")
                    .addArguments("-f", "matroska")
                    .addOutput(UrlOutput.toPath(mksPath))
                    .setOverwriteOutput(true)
                    .setLogLevel(LogLevel.ERROR)
                    .execute();

            // Step 2: mkvextract → .sub + .idx
            ProcessResult mkv = run(new ProcessBuilder(mkvextractPath, mksPath.toString(), "tracks", "0:" + subBase));
            if (!mkv.finished()) {
                log.warn("mkvextract timed out, skipping image subtitle 0:s:{}", subIdx);
                return false;
            }
            if (mkv.exitCode() != 0) {
                log.warn("mkvextract failed (exit {}), skipping image subtitle 0:s:{}. Output tail:\n{}",
                        mkv.exitCode(), subIdx, mkv.outputTail());
                return false;
            }

            // Step 3: subtile-ocr → SRT
            ProcessResult ocr = run(new ProcessBuilder(subtileOcrPath, "-l", lang, "-o", srtPath.toString(), idxPath.toString()));
            if (!ocr.finished()) {
                log.warn("subtile-ocr timed out, skipping image subtitle 0:s:{}", subIdx);
                return false;
            }
            if (ocr.exitCode() != 0) {
                log.warn("subtile-ocr failed (exit {}, lang {}), skipping image subtitle 0:s:{}. Output tail:\n{}",
                        ocr.exitCode(), lang, subIdx, ocr.outputTail());
                return false;
            }

            log.info("OCR-extracted image subtitle 0:s:{} (lang {}) to {}", subIdx, lang, srtPath);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Failed to extract image subtitle (stream 0:s:{}): {}", subIdx, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Failed to extract image subtitle (stream 0:s:{}): {}", subIdx, e.getMessage());
            return false;
        } finally {
            deleteTmpDir(tmpDir);
        }
    }

    /**
     * Runs the process with merged stdout/stderr, draining the output on a
     * background thread so a chatty tool can't dead-lock on a full pipe, and
     * keeping only the tail for the failure logs.
     */
    private ProcessResult run(ProcessBuilder builder) throws IOException, InterruptedException {
        Process process = builder.redirectErrorStream(true).start();
        StringBuilder tail = new StringBuilder();
        Thread drain = Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tail.length() > 4096) {
                        tail.delete(0, tail.length() - 2048);
                    }
                    tail.append(line).append('\n');
                }
            } catch (IOException _) {
                // The stream dies with the process; the tail so far is enough.
            }
        });
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        drain.join(TimeUnit.SECONDS.toMillis(5));
        return new ProcessResult(finished, finished ? process.exitValue() : -1, tail.toString().trim());
    }

    private record ProcessResult(boolean finished, int exitCode, String outputTail) {}

    private void deleteTmpDir(Path tmpDir) {
        try {
            if (Files.exists(tmpDir)) {
                try (var walk = Files.walk(tmpDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException _) {
                            // best-effort cleanup; ignore individual file deletion failures
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean up temp dir {}: {}", tmpDir, e.getMessage());
        }
    }
}
