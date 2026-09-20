package app.ister.disk.events.subtitleextract;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.node.MediaFileInputResolver;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
public class SubtitleExtractor {

    /**
     * Text codecs only. Bitmap subtitles (PGS, VobSub) are not turned into text at all: the
     * transcoder serves their pictures to the player as sprite sheets
     * ({@code HlsBitmapSubtitleService}), lazily, when playback is set up.
     */
    private static final Set<String> TEXT_SUBTITLE_CODECS = Set.of(
            "subrip", "ass", "ssa", "mov_text", "webvtt", "text"
    );

    private final Map<String, String> langMap = new HashMap<>();

    @PostConstruct
    void loadLangMap() {
        var resource = getClass().getResourceAsStream("/iso-639-3.tab");
        if (resource == null) {
            // Reaching this means the resource is missing from the artifact — for the GraalVM
            // native image it must be listed in this module's resource-config.json. Without the
            // map, 639-2/B tags (fre, dut, ger) stay unnormalized and the player's language
            // preferences, which are ISO 639-3, never match them.
            log.error("iso-639-3.tab not found on the classpath; subtitle language normalization is disabled");
            return;
        }
        try (var reader = new BufferedReader(new InputStreamReader(resource))) {
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

    /** One extracted subtitle: the SRT on disk and the language the stream row should carry. */
    public record ExtractedSubtitle(Path srtFile, String language) {
    }

    /**
     * Extracts one embedded subtitle stream to {@code srtDir}.
     *
     * @param input    the media file: a local path, or the owning node's tokenized download URL
     *                 when this node helps with another node's directory
     * @param stream   the {@code SUBTITLE} stream to extract
     * @param subIdx   the stream's index among the file's subtitle streams (ffmpeg's {@code 0:s:N})
     * @return the SRT, or empty when the codec is unsupported or the extraction failed — in the
     *         latter case {@code stream.extractionFailed} is set so the caller can persist it
     */
    public Optional<ExtractedSubtitle> extractOne(String input, UUID mediaFileId,
                                                  MediaFileStreamEntity stream, int subIdx, Path srtDir, String ffmpegDir) {
        String lang = normalizeLanguage(stream.getLanguage());
        Path srtPath = srtDir.resolve(srtFilename(mediaFileId, stream, lang));

        String codecName = stream.getCodecName() != null ? stream.getCodecName().toLowerCase() : "";
        boolean extracted = false;
        boolean attempted = false;

        if (Files.exists(srtPath)) {
            log.debug("SRT already extracted, skipping: {}", srtPath);
            extracted = true;
        } else if (TEXT_SUBTITLE_CODECS.contains(codecName)) {
            attempted = true;
            extracted = extractTextSubtitle(input, subIdx, srtPath, ffmpegDir);
        } else {
            log.debug("Skipping subtitle stream {} with unsupported codec: {}", stream.getStreamIndex(), codecName);
        }

        if (!extracted) {
            if (attempted) {
                // Persisted marker: a redelivered event skips a stream whose extraction already
                // failed; a re-analysis rewrites the stream rows and retries it.
                stream.setExtractionFailed(true);
            }
            return Optional.empty();
        }
        return Optional.of(new ExtractedSubtitle(srtPath, lang));
    }

    /** The SRT file name; the same on every node, so an uploaded copy lands under the name the row records. */
    public String srtFilename(UUID mediaFileId, MediaFileStreamEntity stream, String normalizedLang) {
        return mediaFileId + "_" + stream.getStreamIndex() + "_" + normalizedLang + ".srt";
    }

    /** The {@code EXTERNAL_SUBTITLE} row for an extraction, pointing at {@code recordedPath} (owner-local). */
    public static MediaFileStreamEntity toEntity(MediaFileEntity mediaFile, MediaFileStreamEntity source,
                                                 ExtractedSubtitle extracted, String recordedPath) {
        return MediaFileStreamEntity.builder()
                .mediaFileEntity(mediaFile)
                .streamIndex(source.getStreamIndex())
                .codecName("subtitle srt")
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .language(extracted.language())
                .title(source.getTitle())
                .path(recordedPath)
                .build();
    }

    /** Does the file have any stream this extractor could turn into an SRT? */
    public static boolean isExtractable(MediaFileStreamEntity stream) {
        if (stream.getCodecType() != StreamCodecType.SUBTITLE || stream.getCodecName() == null) {
            return false;
        }
        String codec = stream.getCodecName().toLowerCase();
        return TEXT_SUBTITLE_CODECS.contains(codec);
    }

    private String normalizeLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "und";
        return langMap.getOrDefault(lang, lang);
    }

    private boolean extractTextSubtitle(String inputPath, int subIdx, Path srtPath, String ffmpegDir) {
        try {
            FFmpeg.atPath(Paths.get(ffmpegDir))
                    .addInput(MediaFileInputResolver.ffmpegInput(inputPath))
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
}
