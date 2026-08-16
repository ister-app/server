package app.ister.disk.events.detectsegments;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromaFingerprinterTest {

    private static final int SAMPLE_RATE = AudioPcmReader.SAMPLE_RATE;

    @Test
    void fftFindsThePeakAtTheSineFrequency() {
        int n = ChromaFingerprinter.FFT_SIZE;
        double freq = 440.0;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            re[i] = Math.sin(2 * Math.PI * freq * i / SAMPLE_RATE);
        }
        ChromaFingerprinter.fft(re, im);
        int peakBin = 0;
        double peak = 0;
        for (int bin = 1; bin < n / 2; bin++) {
            double magnitude = Math.hypot(re[bin], im[bin]);
            if (magnitude > peak) {
                peak = magnitude;
                peakBin = bin;
            }
        }
        assertEquals(Math.round(freq * n / SAMPLE_RATE), peakBin);
    }

    @Test
    void fingerprintIsRobustUnderGainChange() {
        short[] loud = melody(20_000, 10);
        short[] soft = new short[loud.length];
        for (int i = 0; i < loud.length; i++) {
            soft[i] = (short) (loud[i] / 4);
        }
        int[] loudPrint = ChromaFingerprinter.fingerprint(loud, SAMPLE_RATE);
        int[] softPrint = ChromaFingerprinter.fingerprint(soft, SAMPLE_RATE);
        // Quantization flips near-tie gradient bits, so not bit-exact — but well within
        // the matcher's Hamming tolerance on average.
        double avgHamming = 0;
        for (int i = 0; i < loudPrint.length; i++) {
            avgHamming += Integer.bitCount(loudPrint[i] ^ softPrint[i]);
        }
        avgHamming /= loudPrint.length;
        assertTrue(avgHamming < 4, "average Hamming distance under gain change was " + avgHamming);
    }

    @Test
    void fingerprintVariesOverAChangingMelody() {
        int[] hashes = ChromaFingerprinter.fingerprint(melody(30_000, 20), SAMPLE_RATE);
        long distinct = java.util.Arrays.stream(hashes).distinct().count();
        assertTrue(distinct > hashes.length / 4,
                "expected varied hashes over a changing melody, got " + distinct + " distinct of " + hashes.length);
    }

    @Test
    void shortInputGivesAnEmptyFingerprint() {
        assertEquals(0, ChromaFingerprinter.fingerprint(new short[100], SAMPLE_RATE).length);
    }

    /**
     * A deterministic pseudo-random melody: a fresh two-tone chord (log-uniform 180–1000 Hz)
     * every 500 ms. Different seeds give uncorrelated melodies, the same seed the same audio.
     */
    static short[] melody(long durationMs, int seed) {
        java.util.Random random = new java.util.Random(seed);
        int samples = (int) (durationMs * SAMPLE_RATE / 1000);
        int stepSamples = SAMPLE_RATE / 2;
        short[] pcm = new short[samples];
        double f1 = 0;
        double f2 = 0;
        for (int i = 0; i < samples; i++) {
            if (i % stepSamples == 0) {
                f1 = 180 * Math.pow(2, random.nextDouble() * 2.5);
                f2 = 180 * Math.pow(2, random.nextDouble() * 2.5);
            }
            // The broadband noise floor matters: without it the empty chroma bins hold only
            // spectral leakage, which looks alike for any chord and matches spuriously.
            double value = Math.sin(2 * Math.PI * f1 * i / SAMPLE_RATE)
                    + 0.6 * Math.sin(2 * Math.PI * f2 * i / SAMPLE_RATE)
                    + 0.4 * (random.nextDouble() - 0.5);
            pcm[i] = (short) (value * 9000);
        }
        return pcm;
    }
}
