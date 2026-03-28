package app.ister.transcoder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioQualityTest {

    @Test
    void fromLabelCopy() {
        assertEquals(AudioQuality.COPY, AudioQuality.fromLabel("copy"));
    }

    @Test
    void fromLabel192k() {
        assertEquals(AudioQuality.Q192K, AudioQuality.fromLabel("192k"));
    }

    @Test
    void fromLabel64k() {
        assertEquals(AudioQuality.Q64K, AudioQuality.fromLabel("64k"));
    }

    @Test
    void fromLabelUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> AudioQuality.fromLabel("999k"));
    }

    @Test
    void copyHasNullBitrate() {
        assertNull(AudioQuality.COPY.getBitrate());
    }

    @Test
    void q192kHasCorrectBitrate() {
        assertEquals("192k", AudioQuality.Q192K.getBitrate());
    }

    @Test
    void q64kHasCorrectBitrate() {
        assertEquals("64k", AudioQuality.Q64K.getBitrate());
    }

    @Test
    void labelsMatchBitrateForTranscoded() {
        assertEquals(AudioQuality.Q192K.getBitrate(), AudioQuality.Q192K.getLabel());
        assertEquals(AudioQuality.Q64K.getBitrate(), AudioQuality.Q64K.getLabel());
    }
}
