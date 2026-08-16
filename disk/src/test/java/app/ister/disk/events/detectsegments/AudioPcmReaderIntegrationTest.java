package app.ister.disk.events.detectsegments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real ffmpeg (must be on PATH, as in CI; silently skipped otherwise) against a
 * WAV written from Java and verifies the decode → fingerprint → match pipeline end to end:
 * the decoded window must fingerprint-match the original samples.
 */
class AudioPcmReaderIntegrationTest {

    private static final String FFMPEG_DIR = "/usr/bin";

    @TempDir
    Path tempDir;

    @Test
    void decodedWindowMatchesTheOriginalAudio() throws IOException {
        assumeTrue(Files.exists(Path.of(FFMPEG_DIR, "ffmpeg")), "ffmpeg not installed");

        short[] original = ChromaFingerprinterTest.melody(30_000, 7);
        Path wav = tempDir.resolve("episode.wav");
        writeWav(wav, original);

        short[] decoded = new AudioPcmReader().readMonoPcm(wav, FFMPEG_DIR, 0, 30_000);

        assertTrue(decoded.length > 25 * AudioPcmReader.SAMPLE_RATE, "decoded window too short: " + decoded.length);
        Optional<SegmentMatcher.Segment> run = SegmentMatcher.longestCommonRun(
                ChromaFingerprinter.fingerprint(original, AudioPcmReader.SAMPLE_RATE),
                ChromaFingerprinter.fingerprint(decoded, AudioPcmReader.SAMPLE_RATE),
                ChromaFingerprinter.hopMillis(AudioPcmReader.SAMPLE_RATE));
        assertTrue(run.isPresent(), "decoded audio does not match the original");
        assertTrue(run.get().lengthMs() >= 25_000, "matched only " + run.get().lengthMs() + "ms");
    }

    @Test
    void offsetSelectsTheRequestedWindow() throws IOException {
        assumeTrue(Files.exists(Path.of(FFMPEG_DIR, "ffmpeg")), "ffmpeg not installed");

        short[] first = ChromaFingerprinterTest.melody(20_000, 1);
        short[] second = ChromaFingerprinterTest.melody(20_000, 2);
        short[] all = new short[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        Path wav = tempDir.resolve("twopart.wav");
        writeWav(wav, all);

        short[] decodedTail = new AudioPcmReader().readMonoPcm(wav, FFMPEG_DIR, 20_000, 20_000);

        Optional<SegmentMatcher.Segment> matchesSecond = SegmentMatcher.longestCommonRun(
                ChromaFingerprinter.fingerprint(second, AudioPcmReader.SAMPLE_RATE),
                ChromaFingerprinter.fingerprint(decodedTail, AudioPcmReader.SAMPLE_RATE),
                ChromaFingerprinter.hopMillis(AudioPcmReader.SAMPLE_RATE));
        assertTrue(matchesSecond.isPresent() && matchesSecond.get().lengthMs() >= 15_000,
                "the tail window should match the second melody");
    }

    /** Minimal mono 16-bit PCM WAV at the fingerprinter's sample rate. */
    private static void writeWav(Path path, short[] pcm) throws IOException {
        int dataSize = pcm.length * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + dataSize).put("WAVE".getBytes());
        header.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(AudioPcmReader.SAMPLE_RATE).putInt(AudioPcmReader.SAMPLE_RATE * 2)
                .putShort((short) 2).putShort((short) 16);
        header.put("data".getBytes()).putInt(dataSize);
        ByteBuffer data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
        data.asShortBuffer().put(pcm);
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(header.array());
            out.write(data.array());
        }
    }
}
