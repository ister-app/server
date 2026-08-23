package app.ister.transcoder;

import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HlsSubtitleServiceTest {

    @Mock
    private Jaffree jaffree;

    @Mock
    private FfprobeService ffprobeService;

    @InjectMocks
    private HlsSubtitleService subject;

    @TempDir
    Path tempDir;

    private static final String MEDIA_FILE_PATH = "/test/video.mkv";

    private MediaFileStreamEntity externalSubtitleStream(UUID id, String srtPath) {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                .codecName("srt")
                .path(srtPath)
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(stream, "id", id);
        return stream;
    }

    private MediaFileStreamEntity embeddedSubtitleStream(UUID id, int streamIndex) {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder()
                .codecType(StreamCodecType.SUBTITLE)
                .codecName("subrip")
                .streamIndex(streamIndex)
                .path("")
                .width(0).height(0)
                .build();
        ReflectionTestUtils.setField(stream, "id", id);
        return stream;
    }

    private Path createSrtFile(String content) throws IOException {
        Path srtFile = tempDir.resolve("sub.srt");
        Files.writeString(srtFile, content);
        return srtFile;
    }

    private Path createCacheDir() throws IOException {
        Path cacheDir = tempDir.resolve("cache");
        Files.createDirectories(cacheDir);
        return cacheDir;
    }

    // ========== generateSubtitleSegments ==========

    @Test
    void generateSubtitleSegmentsWritesVttFileForEachKeyframeWindow() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = createSrtFile("1\n00:00:01,000 --> 00:00:03,000\nHello World\n\n");
        Path cacheDir = createCacheDir();

        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 10.0), Double.NaN, 30.0);

        subject.generateSubtitleSegments(externalSubtitleStream(subtitleId, srtFile.toString()),
                MEDIA_FILE_PATH, UUID.randomUUID(), cacheDir, grid);

        Path segment0 = cacheDir.resolve("seg_sub_" + subtitleId + "_00000.vtt");
        Path segment1 = cacheDir.resolve("seg_sub_" + subtitleId + "_00001.vtt");
        assertTrue(Files.exists(segment0));
        assertTrue(Files.exists(segment1));
        assertTrue(Files.readString(segment0).contains("Hello World"));
    }

    @Test
    void generateSubtitleSegmentsShiftsTimestampsByOffset() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = createSrtFile("1\n00:00:01,000 --> 00:00:03,000\nHello World\n\n");
        Path cacheDir = createCacheDir();

        SegmentGrid grid = SegmentGrid.trim(List.of(0.0), Double.NaN, 10.0);

        subject.generateSubtitleSegments(externalSubtitleStream(subtitleId, srtFile.toString()),
                MEDIA_FILE_PATH, UUID.randomUUID(), cacheDir, grid);

        String vtt = Files.readString(cacheDir.resolve("seg_sub_" + subtitleId + "_00000.vtt"));
        assertTrue(vtt.startsWith("WEBVTT"));
        // Timestamps are shifted by SUBTITLE_OFFSET_MS (1480 ms) and use the WebVTT dot format
        assertTrue(vtt.contains("00:00:02.480 --> 00:00:04.480"));
    }

    @Test
    void generateSubtitleSegmentsWritesHeaderOnlySegmentWhenNoCuesInWindow() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = createSrtFile("1\n00:00:01,000 --> 00:00:03,000\nOnly in first segment\n\n");
        Path cacheDir = createCacheDir();

        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 10.0), Double.NaN, 30.0);

        subject.generateSubtitleSegments(externalSubtitleStream(subtitleId, srtFile.toString()),
                MEDIA_FILE_PATH, UUID.randomUUID(), cacheDir, grid);

        String segment1 = Files.readString(cacheDir.resolve("seg_sub_" + subtitleId + "_00001.vtt"));
        assertEquals("WEBVTT\n", segment1);
    }

    @Test
    void generateSubtitleSegmentsWritesCueOnlyIntoItsStartSegment() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        // Cue from 8s to 12s spans the segment boundary at 10s. It must appear
        // only in the segment it starts in: repeating it into every overlapping
        // segment makes mpv's HLS demuxer feed the copy to the renderer again,
        // stacking the same line multiple times on screen.
        Path srtFile = createSrtFile("1\n00:00:08,000 --> 00:00:12,000\nSpanning\n\n");
        Path cacheDir = createCacheDir();

        SegmentGrid grid = SegmentGrid.trim(List.of(0.0, 10.0), Double.NaN, 20.0);

        subject.generateSubtitleSegments(externalSubtitleStream(subtitleId, srtFile.toString()),
                MEDIA_FILE_PATH, UUID.randomUUID(), cacheDir, grid);

        assertTrue(Files.readString(cacheDir.resolve("seg_sub_" + subtitleId + "_00000.vtt")).contains("Spanning"));
        assertFalse(Files.readString(cacheDir.resolve("seg_sub_" + subtitleId + "_00001.vtt")).contains("Spanning"));
    }

    @Test
    void generateSubtitleSegmentsParsesWindowsLineEndingsAndMissingCueNumbers() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        // Windows line endings, no cue numbers
        Path srtFile = createSrtFile("00:00:01,000 --> 00:00:03,000\r\nFirst\r\n\r\n00:00:04,000 --> 00:00:05,000\r\nSecond\r\n");
        Path cacheDir = createCacheDir();

        SegmentGrid grid = SegmentGrid.trim(List.of(0.0), Double.NaN, 10.0);

        subject.generateSubtitleSegments(externalSubtitleStream(subtitleId, srtFile.toString()),
                MEDIA_FILE_PATH, UUID.randomUUID(), cacheDir, grid);

        String vtt = Files.readString(cacheDir.resolve("seg_sub_" + subtitleId + "_00000.vtt"));
        assertTrue(vtt.contains("First"));
        assertTrue(vtt.contains("Second"));
    }

    @Test
    void generateSubtitleSegmentsPreservesMultiLineCueText() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = createSrtFile("1\n00:00:01,000 --> 00:00:03,000\nLine one\nLine two\n\n");
        Path cacheDir = createCacheDir();

        SegmentGrid grid = SegmentGrid.trim(List.of(0.0), Double.NaN, 10.0);

        subject.generateSubtitleSegments(externalSubtitleStream(subtitleId, srtFile.toString()),
                MEDIA_FILE_PATH, UUID.randomUUID(), cacheDir, grid);

        String vtt = Files.readString(cacheDir.resolve("seg_sub_" + subtitleId + "_00000.vtt"));
        assertTrue(vtt.contains("Line one\nLine two"));
    }

    @Test
    void generateSubtitleSegmentsSkipsCuesWithMalformedTimestamps() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        Path srtFile = createSrtFile("""
                1
                garbage --> nonsense
                Bad cue

                2
                00:00:02,000 --> 00:00:04,000
                Good cue

                """);
        Path cacheDir = createCacheDir();

        SegmentGrid grid = SegmentGrid.trim(List.of(0.0), Double.NaN, 10.0);

        subject.generateSubtitleSegments(externalSubtitleStream(subtitleId, srtFile.toString()),
                MEDIA_FILE_PATH, UUID.randomUUID(), cacheDir, grid);

        String vtt = Files.readString(cacheDir.resolve("seg_sub_" + subtitleId + "_00000.vtt"));
        assertFalse(vtt.contains("Bad cue"));
        assertTrue(vtt.contains("Good cue"));
    }

    @Test
    void generateSubtitleSegmentsExtractsEmbeddedSubtitleViaFfmpeg() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        Path cacheDir = tempDir.resolve("cache");

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);
        doAnswer(inv -> {
            Files.writeString(cacheDir.resolve("sub_" + subtitleId + ".srt"),
                    "1\n00:00:01,000 --> 00:00:03,000\nEmbedded text\n\n");
            return null;
        }).when(ffmpegMock).execute();

        SegmentGrid grid = SegmentGrid.trim(List.of(0.0), Double.NaN, 10.0);

        subject.generateSubtitleSegments(embeddedSubtitleStream(subtitleId, 2),
                MEDIA_FILE_PATH, UUID.randomUUID(), cacheDir, grid);

        verify(ffmpegMock).execute();
        String vtt = Files.readString(cacheDir.resolve("seg_sub_" + subtitleId + "_00000.vtt"));
        assertTrue(vtt.contains("Embedded text"));
    }

    // ========== extractEmbeddedSubtitleToSrt ==========

    @Test
    void extractEmbeddedSubtitleToSrtCreatesCacheDirWhenMissing() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        Path cacheDir = tempDir.resolve("does-not-exist-yet");

        FFmpeg ffmpegMock = mock(FFmpeg.class, RETURNS_SELF);
        when(jaffree.getFFMPEG()).thenReturn(ffmpegMock);

        String result = subject.extractEmbeddedSubtitleToSrt(embeddedSubtitleStream(subtitleId, 3),
                MEDIA_FILE_PATH, cacheDir);

        assertTrue(Files.isDirectory(cacheDir));
        assertEquals(cacheDir.resolve("sub_" + subtitleId + ".srt").toString(), result);
        verify(ffmpegMock).execute();
    }

    @Test
    void extractEmbeddedSubtitleToSrtReturnsCachedFileWithoutRunningFfmpeg() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        Path cacheDir = createCacheDir();
        Path existingSrt = cacheDir.resolve("sub_" + subtitleId + ".srt");
        Files.writeString(existingSrt, "1\n00:00:01,000 --> 00:00:03,000\nCached\n\n");

        String result = subject.extractEmbeddedSubtitleToSrt(embeddedSubtitleStream(subtitleId, 3),
                MEDIA_FILE_PATH, cacheDir);

        assertEquals(existingSrt.toString(), result);
        verifyNoInteractions(jaffree);
    }

    // ========== writeSrtWithOffset ==========

    @Test
    void writeSrtWithOffsetShiftsAllTimestampsAndRenumbersCues() throws IOException {
        // Source has non-sequential cue numbers; output must be renumbered 1..n
        Path source = createSrtFile("""
                5
                00:00:01,000 --> 00:00:03,000
                First

                9
                00:01:00,500 --> 00:01:02,000
                Second

                """);
        Path output = tempDir.resolve("out.srt");

        subject.writeSrtWithOffset(source.toString(), output);

        String content = Files.readString(output);
        // Timestamps shifted by SUBTITLE_OFFSET_MS (1480 ms), SRT comma format
        assertTrue(content.contains("1\n00:00:02,480 --> 00:00:04,480\nFirst"));
        assertTrue(content.contains("2\n00:01:01,980 --> 00:01:03,480\nSecond"));
    }

    @Test
    void writeSrtWithOffsetIsNoOpWhenOutputExists() throws IOException {
        Path source = createSrtFile("1\n00:00:01,000 --> 00:00:03,000\nNew content\n\n");
        Path output = tempDir.resolve("out.srt");
        Files.writeString(output, "sentinel");

        subject.writeSrtWithOffset(source.toString(), output);

        assertEquals("sentinel", Files.readString(output));
    }

    @Test
    void writeSrtWithOffsetParsesDotSeparatorAndPadsMilliseconds() throws IOException {
        // Dot instead of comma, short millisecond parts (".5" -> 500 ms, ".25" -> 250 ms)
        Path source = createSrtFile("1\n00:00:01.5 --> 00:00:02.25\nText\n\n");
        Path output = tempDir.resolve("out.srt");

        subject.writeSrtWithOffset(source.toString(), output);

        String content = Files.readString(output);
        // 1500 + 1480 = 2980 ms; 2250 + 1480 = 3730 ms
        assertTrue(content.contains("00:00:02,980 --> 00:00:03,730"));
    }

    @Test
    void sanitizeClampsSentinelDurationsToNextCueStart() {
        // FFmpeg's "unknown duration" extraction: end = start + 655,350 ms.
        var cues = List.of(
                new HlsSubtitleService.SrtCue(3_565, 3_565 + 655_350, "one"),
                new HlsSubtitleService.SrtCue(5_901, 5_901 + 655_350, "two"),
                new HlsSubtitleService.SrtCue(9_363, 9_363 + 655_350, "three"));

        var result = HlsSubtitleService.sanitizeCueDurations(cues);

        assertEquals(5_901, result.get(0).endMs());
        assertEquals(9_363, result.get(1).endMs());
        // Last cue has no follow-up: short default duration.
        assertEquals(9_363 + HlsSubtitleService.DEFAULT_CUE_DURATION_MS, result.get(2).endMs());
    }

    @Test
    void sanitizeCapsClampAtDefaultDurationWhenNextCueIsFarAway() {
        var cues = List.of(
                new HlsSubtitleService.SrtCue(1_000, 1_000 + 655_350, "one"),
                new HlsSubtitleService.SrtCue(120_000, 123_000, "two"));

        var result = HlsSubtitleService.sanitizeCueDurations(cues);

        // A long dialogue pause must not keep the broken cue on screen.
        assertEquals(1_000 + HlsSubtitleService.DEFAULT_CUE_DURATION_MS, result.get(0).endMs());
        // Sane cues stay untouched.
        assertEquals(123_000, result.get(1).endMs());
    }

    @Test
    void sanitizeLeavesNormalCuesAlone() {
        var cues = List.of(
                new HlsSubtitleService.SrtCue(1_000, 3_500, "one"),
                new HlsSubtitleService.SrtCue(4_000, 29_000, "long but plausible"));

        var result = HlsSubtitleService.sanitizeCueDurations(cues);

        assertEquals(3_500, result.get(0).endMs());
        assertEquals(29_000, result.get(1).endMs());
    }

    @Test
    void generationMarkerRoundTrip() throws IOException {
        UUID subtitleId = UUID.randomUUID();
        assertFalse(subject.isGenerationCurrent(tempDir, subtitleId));

        Files.writeString(tempDir.resolve("seg_sub_" + subtitleId + ".gen"), "1");
        assertFalse(subject.isGenerationCurrent(tempDir, subtitleId));

        Files.writeString(tempDir.resolve("seg_sub_" + subtitleId + ".gen"),
                String.valueOf(HlsSubtitleService.SUBTITLE_GENERATION));
        assertTrue(subject.isGenerationCurrent(tempDir, subtitleId));
    }
}
