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
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class MediaFileFoundExtractSubtitles {

    private static final Set<String> TEXT_SUBTITLE_CODECS = Set.of(
            "subrip", "ass", "ssa", "mov_text", "webvtt", "text"
    );
    private static final Set<String> IMAGE_SUBTITLE_CODECS = Set.of(
            "dvd_subtitle", "dvdsub", "hdmv_pgs_subtitle", "pgssub"
    );

    @Value("${app.ister.server.subtile-ocr:/usr/bin/subtile-ocr}")
    private String subtileOcrPath;

    @Value("${app.ister.server.mkvextract:/usr/bin/mkvextract}")
    private String mkvextractPath;

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
            String lang = normalizeLanguage(stream.getLanguage());
            String srtFilename = mediaFile.getId() + "_" + stream.getStreamIndex() + "_" + lang + ".srt";
            Path srtPath = Paths.get(cacheDir.getPath(), srtFilename);

            String codecName = stream.getCodecName() != null ? stream.getCodecName().toLowerCase() : "";
            boolean extracted = false;

            if (TEXT_SUBTITLE_CODECS.contains(codecName)) {
                extracted = extractTextSubtitle(mediaFile.getPath(), subIdx, srtPath, ffmpegDir);
            } else if (IMAGE_SUBTITLE_CODECS.contains(codecName)) {
                extracted = extractImageSubtitle(mediaFile.getPath(), subIdx, srtPath, lang, ffmpegDir);
            } else {
                log.debug("Skipping subtitle stream {} with unsupported codec: {}", stream.getStreamIndex(), codecName);
            }

            if (extracted) {
                result.add(MediaFileStreamEntity.builder()
                        .mediaFileEntity(mediaFile)
                        .streamIndex(stream.getStreamIndex())
                        .codecName("subtitle srt")
                        .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                        .language(lang)
                        .title(stream.getTitle())
                        .path(srtPath.toString())
                        .build());
            }
            subIdx++;
        }
        return result;
    }

    private String normalizeLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "und";
        return langMap.getOrDefault(lang, lang);
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
        Path tmpDir = srtPath.getParent().resolve(".sub_tmp_" + ProcessHandle.current().pid());
        try {
            Files.createDirectories(tmpDir);
            Path mksPath = tmpDir.resolve(lang + "_" + subIdx + ".mks");
            String subBase = tmpDir.resolve(lang + "_" + subIdx).toString();
            Path idxPath = tmpDir.resolve(lang + "_" + subIdx + ".idx");

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
            Process mkvProcess = new ProcessBuilder(mkvextractPath, mksPath.toString(), "tracks", "0:" + subBase)
                    .redirectErrorStream(true)
                    .start();
            if (!mkvProcess.waitFor(10, TimeUnit.MINUTES)) {
                mkvProcess.destroyForcibly();
                log.warn("mkvextract timed out, skipping image subtitle 0:s:{}", subIdx);
                return false;
            }
            if (mkvProcess.exitValue() != 0) {
                log.warn("mkvextract failed (exit {}), skipping image subtitle 0:s:{}", mkvProcess.exitValue(), subIdx);
                return false;
            }

            // Step 3: subtile-ocr → SRT
            Process ocrProcess = new ProcessBuilder(subtileOcrPath, "-l", lang, "-o", srtPath.toString(), idxPath.toString())
                    .redirectErrorStream(true)
                    .start();
            if (!ocrProcess.waitFor(10, TimeUnit.MINUTES)) {
                ocrProcess.destroyForcibly();
                log.warn("subtile-ocr timed out, skipping image subtitle 0:s:{}", subIdx);
                return false;
            }
            if (ocrProcess.exitValue() != 0) {
                log.warn("subtile-ocr failed (exit {}), skipping image subtitle 0:s:{}", ocrProcess.exitValue(), subIdx);
                return false;
            }

            log.debug("Extracted image subtitle via OCR to {}", srtPath);
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
