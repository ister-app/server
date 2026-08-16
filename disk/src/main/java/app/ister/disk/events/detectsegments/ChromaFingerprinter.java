package app.ister.disk.events.detectsegments;

/**
 * Turns mono 16 kHz PCM into a sequence of 32-bit audio hashes, one per {@link #HOP_SIZE} samples
 * (128 ms). Each hash encodes only the <em>signs of gradients</em> — which pitch class is louder
 * than its neighbour, whether a band got louder since the previous frame — so the same recording
 * matches across episodes even when mastered at different loudness. Same idea as chromaprint;
 * implemented here because ffmpeg's chromaprint muxer is a compile-time option we cannot assume
 * of the deployment-provided ffmpeg.
 */
final class ChromaFingerprinter {

    static final int FFT_SIZE = 4096;
    static final int HOP_SIZE = 2048;

    /** Frequency range mapped to chroma and energy bands: low male voice up to melody range. */
    static final double MIN_FREQ = 62.0;
    static final double MAX_FREQ = 3500.0;

    /**
     * Frames whose raw PCM RMS is below this (~-56 dBFS on 16-bit) are marked unvoiced: gradient
     * hashes of near-silence are pure noise-floor comparisons, identical for any two silent
     * passages, and would otherwise "match" across episodes — the all-silent fixture files
     * produced a bogus whole-window outro exactly that way.
     */
    static final double SILENCE_RMS = 50.0;

    private static final int CHROMA_BINS = 12;
    private static final int ENERGY_BANDS = 8;

    private ChromaFingerprinter() {
    }

    /** A hash sequence plus, per frame, whether the audio was loud enough to be meaningful. */
    record Fingerprint(int[] hashes, boolean[] voiced) {
        int length() {
            return hashes.length;
        }

        // Content-based equality: the default record implementation compares array identity.
        @Override
        public boolean equals(Object other) {
            return other instanceof Fingerprint(int[] otherHashes, boolean[] otherVoiced)
                    && java.util.Arrays.equals(hashes, otherHashes)
                    && java.util.Arrays.equals(voiced, otherVoiced);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(hashes) + java.util.Arrays.hashCode(voiced);
        }

        @Override
        public String toString() {
            return "Fingerprint[frames=" + hashes.length + "]";
        }
    }

    static long hopMillis(int sampleRate) {
        return HOP_SIZE * 1000L / sampleRate;
    }

    /** One 32-bit hash per hop; empty input (or shorter than one frame) gives an empty result. */
    static Fingerprint fingerprint(short[] pcm, int sampleRate) {
        if (pcm.length < FFT_SIZE) {
            return new Fingerprint(new int[0], new boolean[0]);
        }
        int frames = 1 + (pcm.length - FFT_SIZE) / HOP_SIZE;
        int[] chromaBinOfFftBin = binMapping(sampleRate, true);
        int[] bandOfFftBin = binMapping(sampleRate, false);
        double[] window = hannWindow();

        int[] hashes = new int[frames];
        boolean[] voiced = new boolean[frames];
        double[] prevChroma = null;
        double[] prevBands = null;
        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];
        for (int f = 0; f < frames; f++) {
            int offset = f * HOP_SIZE;
            double energy = 0;
            for (int i = 0; i < FFT_SIZE; i++) {
                re[i] = pcm[offset + i] * window[i];
                im[i] = 0;
                energy += (double) pcm[offset + i] * pcm[offset + i];
            }
            voiced[f] = Math.sqrt(energy / FFT_SIZE) >= SILENCE_RMS;
            fft(re, im);
            double[] chroma = new double[CHROMA_BINS];
            double[] bands = new double[ENERGY_BANDS];
            for (int bin = 1; bin < FFT_SIZE / 2; bin++) {
                if (chromaBinOfFftBin[bin] < 0) {
                    continue;
                }
                double magnitude = Math.hypot(re[bin], im[bin]);
                chroma[chromaBinOfFftBin[bin]] += magnitude;
                bands[bandOfFftBin[bin]] += magnitude;
            }
            if (prevChroma == null) {
                prevChroma = chroma;
                prevBands = bands;
            }
            hashes[f] = hash(chroma, prevChroma, bands, prevBands);
            prevChroma = chroma;
            prevBands = bands;
        }
        return new Fingerprint(hashes, voiced);
    }

    /**
     * Bits 0–11: chroma[c] > chroma[(c+1) % 12] (intra-frame gradient). Bits 12–23: chroma got
     * louder than the previous frame. Bits 24–31: energy band got louder than the previous frame.
     */
    private static int hash(double[] chroma, double[] prevChroma, double[] bands, double[] prevBands) {
        int h = 0;
        for (int c = 0; c < CHROMA_BINS; c++) {
            if (chroma[c] > chroma[(c + 1) % CHROMA_BINS]) {
                h |= 1 << c;
            }
            if (chroma[c] > prevChroma[c]) {
                h |= 1 << (CHROMA_BINS + c);
            }
        }
        for (int b = 0; b < ENERGY_BANDS; b++) {
            if (bands[b] > prevBands[b]) {
                h |= 1 << (2 * CHROMA_BINS + b);
            }
        }
        return h;
    }

    /**
     * For every FFT bin: its chroma pitch class ({@code chroma=true}) or its log-spaced energy
     * band, or -1 for bins outside {@link #MIN_FREQ}–{@link #MAX_FREQ}.
     */
    private static int[] binMapping(int sampleRate, boolean chroma) {
        int[] mapping = new int[FFT_SIZE / 2];
        double logMin = Math.log(MIN_FREQ);
        double logMax = Math.log(MAX_FREQ);
        for (int bin = 0; bin < FFT_SIZE / 2; bin++) {
            double freq = (double) bin * sampleRate / FFT_SIZE;
            if (freq < MIN_FREQ || freq > MAX_FREQ) {
                mapping[bin] = -1;
            } else if (chroma) {
                mapping[bin] = Math.floorMod((int) Math.round(CHROMA_BINS * log2(freq / 440.0)), CHROMA_BINS);
            } else {
                int band = (int) (ENERGY_BANDS * (Math.log(freq) - logMin) / (logMax - logMin));
                mapping[bin] = Math.min(band, ENERGY_BANDS - 1);
            }
        }
        return mapping;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static double[] hannWindow() {
        double[] window = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            window[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
        }
        return window;
    }

    /** In-place iterative radix-2 FFT; length must be a power of two. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        // Bit-reversal permutation. `reversed` is the running bit-reversed index of i.
        int reversed = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            for (; (reversed & bit) != 0; bit >>= 1) {
                reversed ^= bit;
            }
            reversed |= bit;
            if (i < reversed) {
                double t = re[i]; re[i] = re[reversed]; re[reversed] = t;
                t = im[i]; im[i] = im[reversed]; im[reversed] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curRe = 1;
                double curIm = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j;
                    int b = i + j + len / 2;
                    double tRe = re[b] * curRe - im[b] * curIm;
                    double tIm = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - tRe;
                    im[b] = im[a] - tIm;
                    re[a] += tRe;
                    im[a] += tIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
