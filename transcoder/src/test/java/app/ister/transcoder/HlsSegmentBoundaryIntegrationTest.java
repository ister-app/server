package app.ister.transcoder;

import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import com.github.kokorin.jaffree.ffprobe.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real FFmpeg passes (ffmpeg must be on PATH, as in CI) and verifies that the
 * produced segments concatenate into a clean MPEG-TS stream: an HLS client reads them
 * back to back, and any packet flagged corrupt at a segment boundary is a decode hiccup
 * every few seconds of playback. Regression test for the unterminated final PES packet
 * the segment muxer emits without {@code omit_video_pes_length=0}.
 */
class HlsSegmentBoundaryIntegrationTest {

    @TempDir
    Path tempDir;

    private HlsTranscodeService service;
    private Jaffree jaffree;

    @BeforeEach
    void setUp() {
        jaffree = new Jaffree();
        ReflectionTestUtils.setField(jaffree, "dirOfFFmpeg", "/usr/bin");
        FfprobeService ffprobeService = new FfprobeService(jaffree);
        service = new HlsTranscodeService(jaffree, ffprobeService);
        ReflectionTestUtils.setField(service, "tmpDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "segmentTimeoutMs", 60000L);
        ReflectionTestUtils.setField(service, "hwaccelProperty", "none");
        ReflectionTestUtils.setField(service, "maxConcurrentFiles", 2);
        ReflectionTestUtils.setField(service, "maxConcurrentPasses", 4);
        ReflectionTestUtils.setField(service, "maxBackgroundFiles", 10);
        ReflectionTestUtils.setField(service, "maxBackgroundPasses", 4);
        ReflectionTestUtils.setField(service, "segmentStabilityMs", 200L);
        ReflectionTestUtils.setField(service, "passTimeoutMultiplier", 4.0);
        ReflectionTestUtils.setField(service, "passTimeoutMinSeconds", 120L);
        ReflectionTestUtils.setField(service, "passStallTimeoutSeconds", 60L);
        ReflectionTestUtils.setField(service, "cacheRetentionHours", 2L);
        service.init();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        service.transcodeExecutor.shutdown();
        if (!service.transcodeExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            service.transcodeExecutor.shutdownNow();
        }
    }

    /** 12s of 2s-GOP h264 with an AC-3 (MPEG-TS-native) audio track. */
    private Path generateSource() {
        Path src = tempDir.resolve("src.mkv");
        jaffree.getFFMPEG()
                .addInput(UrlInput.fromUrl("testsrc2=size=640x360:rate=24:duration=12")
                        .setFormat("lavfi"))
                .addInput(UrlInput.fromUrl("sine=frequency=440:duration=12")
                        .setFormat("lavfi"))
                .addOutput(UrlOutput.toPath(src)
                        .addArguments("-c:v", "libx264")
                        .addArguments("-g", "48")
                        .addArguments("-keyint_min", "48")
                        .addArguments("-c:a", "ac3")
                        .addArguments("-b:a", "192k"))
                .setOverwriteOutput(true)
                .setLogLevel(LogLevel.ERROR)
                .execute();
        return src;
    }

    private Path concatenate(String prefix) throws IOException {
        // The name must not match the prefix filter below, or it would append itself forever.
        Path all = tempDir.resolve("concat_" + prefix + "result");
        try (OutputStream out = Files.newOutputStream(all);
             Stream<Path> files = Files.list(tempDir)) {
            files.filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".ts"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            Files.copy(p, out);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        }
        return all;
    }

    private List<Packet> packetsOf(Path tsFile) {
        return jaffree.getFFPROBE()
                .setShowPackets(true)
                .setInput(tsFile)
                .setLogLevel(LogLevel.ERROR)
                .execute()
                .getPackets();
    }

    private void assertNoCorruptPackets(Path concatenated) {
        List<Packet> packets = packetsOf(concatenated);
        assertFalse(packets.isEmpty(), "expected packets in " + concatenated);
        List<Packet> corrupt = packets.stream()
                .filter(p -> p.getFlags() != null && p.getFlags().contains("C"))
                .toList();
        assertTrue(corrupt.isEmpty(), () -> corrupt.size()
                + " corrupt-flagged packets at segment boundaries, first at dts_time="
                + corrupt.getFirst().getDtsTime());
    }

    @Test
    void copyVideoSegmentsConcatenateWithoutCorruptPackets() throws IOException {
        Path src = generateSource();

        service.startVideoPass(src.toString(), tempDir, VideoQuality.COPY);

        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().equals("seg_video_copy_00001.ts")),
                    "expected the copy pass to produce multiple segments");
        }
        assertNoCorruptPackets(concatenate("seg_video_copy_"));
    }

    @Test
    void copyAudioSegmentsConcatenateWithoutCorruptPackets() throws IOException {
        Path src = generateSource();

        // Stream index 1 is the AC-3 audio track; MPEG-TS-native, so the pass stream-copies it.
        service.startAudioPass(src.toString(), tempDir, 1, AudioQuality.COPY, "ac3");

        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().equals("seg_audio_1_copy_00001.ts")),
                    "expected the copy audio pass to produce multiple segments");
        }
        assertNoCorruptPackets(concatenate("seg_audio_1_copy_"));
    }

    @Test
    void transcodedVideoSegmentsConcatenateWithoutCorruptPackets() throws IOException {
        Path src = generateSource();

        service.startVideoPass(src.toString(), tempDir, VideoQuality.Q480P);

        assertNoCorruptPackets(concatenate("seg_video_480p_"));
    }
}
