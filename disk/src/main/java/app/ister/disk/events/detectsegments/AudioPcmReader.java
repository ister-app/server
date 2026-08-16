package app.ister.disk.events.detectsegments;

import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.PipeOutput;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Decodes a window of a media file's first audio stream to mono 16 kHz s16 PCM for
 * fingerprinting. 16 kHz keeps every chroma band the fingerprinter uses (62 Hz–3.5 kHz)
 * while staying cheap: a 10-minute window is ~19 MB of samples.
 */
@Component
@Slf4j
public class AudioPcmReader {

    public static final int SAMPLE_RATE = 16_000;

    /**
     * The decoded window, or an empty array when decoding failed (no audio stream,
     * unreadable file) — the caller then skips the episode for this run.
     */
    public short[] readMonoPcm(Path mediaFilePath, String dirOfFFmpeg, long offsetMs, long durationMs) {
        if (durationMs <= 0) {
            return new short[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            FFmpeg.atPath(Path.of(dirOfFFmpeg))
                    .addInput(UrlInput.fromPath(mediaFilePath)
                            .addArguments("-ss", offsetMs + "ms"))
                    .addOutput(PipeOutput.pumpTo(out)
                            .setFormat("s16le")
                            .addArguments("-map", "0:a:0")
                            .addArguments("-ac", "1")
                            .addArguments("-ar", String.valueOf(SAMPLE_RATE))
                            .addArguments("-t", durationMs + "ms"))
                    .setLogLevel(LogLevel.ERROR)
                    .execute();
        } catch (Exception e) {
            log.warn("PCM decode at {}ms failed for {}: {}", offsetMs, mediaFilePath, e.getMessage());
            return new short[0];
        }
        byte[] bytes = out.toByteArray();
        short[] pcm = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        return pcm;
    }
}
