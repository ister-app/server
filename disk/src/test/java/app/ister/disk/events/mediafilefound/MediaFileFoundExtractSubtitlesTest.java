package app.ister.disk.events.mediafilefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MediaFileFoundExtractSubtitlesTest {

    @TempDir
    Path cacheDir;

    private MediaFileFoundExtractSubtitles subject;
    private MediaFileEntity mediaFile;
    private DirectoryEntity cacheDirEntity;

    @BeforeEach
    void setUp() {
        subject = new MediaFileFoundExtractSubtitles();
        subject.loadLangMap();

        mediaFile = MediaFileEntity.builder()
                .id(UUID.randomUUID())
                .path("/test/video.mkv")
                .build();
        cacheDirEntity = DirectoryEntity.builder()
                .path(cacheDir.toString())
                .build();
    }

    @Test
    void extractSubtitlesReturnsEmptyListWhenNoStreams() {
        List<MediaFileStreamEntity> result = subject.extractSubtitles(mediaFile, List.of(), cacheDirEntity, "/usr/bin");
        assertTrue(result.isEmpty());
    }

    @Test
    void extractSubtitlesSkipsNonSubtitleStreams() {
        MediaFileStreamEntity videoStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.VIDEO)
                .streamIndex(0)
                .build();

        List<MediaFileStreamEntity> result = subject.extractSubtitles(
                mediaFile, List.of(videoStream), cacheDirEntity, "/usr/bin");

        assertTrue(result.isEmpty());
    }

    @Test
    void extractSubtitlesReturnsCachedSrtWhenAlreadyExists() throws IOException {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("subrip")
                .streamIndex(5)
                .language("eng")
                .title("English")
                .build();

        String srtFilename = mediaFile.getId() + "_5_eng.srt";
        Path srtPath = cacheDir.resolve(srtFilename);
        Files.writeString(srtPath, "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n");

        List<MediaFileStreamEntity> result = subject.extractSubtitles(
                mediaFile, List.of(stream), cacheDirEntity, "/usr/bin");

        assertEquals(1, result.size());
        MediaFileStreamEntity extracted = result.get(0);
        assertEquals("subtitle srt", extracted.getCodecName());
        assertEquals(StreamCodecType.EXTERNAL_SUBTITLE, extracted.getCodecType());
        assertEquals("eng", extracted.getLanguage());
        assertEquals("English", extracted.getTitle());
        assertEquals(srtPath.toString(), extracted.getPath());
        assertEquals(5, extracted.getStreamIndex());
    }

    static Stream<String> codecsThatProduceEmptyResult() {
        return Stream.of(null, "unknown_codec", "subrip", "dvd_subtitle");
    }

    @ParameterizedTest
    @MethodSource("codecsThatProduceEmptyResult")
    void extractSubtitlesReturnsEmptyForUnprocessableCodec(String codec) {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName(codec)
                .streamIndex(0)
                .language("eng")
                .build();

        List<MediaFileStreamEntity> result = subject.extractSubtitles(
                mediaFile, List.of(stream), cacheDirEntity, "/nonexistent/ffmpeg/dir");

        assertTrue(result.isEmpty());
    }

    static Stream<Arguments> languageNormalizationCases() {
        return Stream.of(
                Arguments.of(null, "und"),
                Arguments.of("   ", "und"),
                Arguments.of("ger", "deu"),
                Arguments.of("xyz-invented", "xyz-invented")
        );
    }

    @ParameterizedTest
    @MethodSource("languageNormalizationCases")
    void extractSubtitlesNormalizesLanguage(String inputLang, String expectedLang) throws IOException {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("subrip")
                .streamIndex(0)
                .language(inputLang)
                .build();

        Files.writeString(cacheDir.resolve(mediaFile.getId() + "_0_" + expectedLang + ".srt"), "srt");

        List<MediaFileStreamEntity> result = subject.extractSubtitles(
                mediaFile, List.of(stream), cacheDirEntity, "/usr/bin");

        assertEquals(1, result.size());
        assertEquals(expectedLang, result.get(0).getLanguage());
    }

    @Test
    void extractSubtitlesCallsTextExtractionForAllTextCodecs() {
        for (String codec : List.of("ass", "ssa", "mov_text", "webvtt", "text")) {
            MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                    .codecType(StreamCodecType.SUBTITLE)
                    .codecName(codec)
                    .streamIndex(0)
                    .language("eng")
                    .build();

            List<MediaFileStreamEntity> result = subject.extractSubtitles(
                    mediaFile, List.of(stream), cacheDirEntity, "/nonexistent/ffmpeg/dir");

            assertTrue(result.isEmpty(), "Expected empty result for codec: " + codec);
        }
    }

    @Test
    void extractSubtitlesCallsImageExtractionForAllImageCodecs() {
        for (String codec : List.of("dvdsub", "hdmv_pgs_subtitle", "pgssub")) {
            MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                    .codecType(StreamCodecType.SUBTITLE)
                    .codecName(codec)
                    .streamIndex(0)
                    .language("eng")
                    .build();

            List<MediaFileStreamEntity> result = subject.extractSubtitles(
                    mediaFile, List.of(stream), cacheDirEntity, "/nonexistent/ffmpeg/dir");

            assertTrue(result.isEmpty(), "Expected empty result for codec: " + codec);
        }
    }

    @Test
    void extractSubtitlesHandlesMultipleStreamsCorrectly() throws IOException {
        MediaFileStreamEntity audioStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.AUDIO)
                .streamIndex(0)
                .build();

        MediaFileStreamEntity cachedSub = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("ass")
                .streamIndex(1)
                .language("jpn")
                .build();
        Files.writeString(cacheDir.resolve(mediaFile.getId() + "_1_jpn.srt"), "srt");

        MediaFileStreamEntity unsupportedSub = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("unknown_format")
                .streamIndex(2)
                .language("fra")
                .build();

        List<MediaFileStreamEntity> result = subject.extractSubtitles(
                mediaFile,
                List.of(audioStream, cachedSub, unsupportedSub),
                cacheDirEntity,
                "/usr/bin");

        assertEquals(1, result.size());
        assertEquals("jpn", result.get(0).getLanguage());
    }

    @Test
    void extractSubtitlesSubIdxIncrementedOnlyForSubtitleStreams() throws IOException {
        // Both subtitle streams are indexed by subtitle-only index (subIdx), not streamIndex
        // Stream 0 = audio (skipped, subIdx stays 0)
        // Stream 1 = subtitle (subIdx=0 after loop)
        // Stream 2 = subtitle (subIdx=1 after loop)
        MediaFileStreamEntity audioStream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.AUDIO)
                .streamIndex(0)
                .build();

        MediaFileStreamEntity sub1 = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("subrip")
                .streamIndex(1)
                .language("eng")
                .build();
        Files.writeString(cacheDir.resolve(mediaFile.getId() + "_1_eng.srt"), "srt1");

        MediaFileStreamEntity sub2 = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("subrip")
                .streamIndex(2)
                .language("fra")
                .build();
        Files.writeString(cacheDir.resolve(mediaFile.getId() + "_2_fra.srt"), "srt2");

        List<MediaFileStreamEntity> result = subject.extractSubtitles(
                mediaFile,
                List.of(audioStream, sub1, sub2),
                cacheDirEntity,
                "/usr/bin");

        assertEquals(2, result.size());
    }
}
