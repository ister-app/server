package app.ister.transcoder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VideoQualityTest {

    @Test
    void fromLabelCopy() {
        assertEquals(VideoQuality.COPY, VideoQuality.fromLabel("copy"));
    }

    @Test
    void fromLabel720p() {
        assertEquals(VideoQuality.Q720P, VideoQuality.fromLabel("720p"));
    }

    @Test
    void fromLabel480p() {
        assertEquals(VideoQuality.Q480P, VideoQuality.fromLabel("480p"));
    }

    @Test
    void fromLabelUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> VideoQuality.fromLabel("1080p"));
    }

    @Test
    void copyHasNullScaleCodecAndBitrate() {
        assertNull(VideoQuality.COPY.getScale());
        assertNull(VideoQuality.COPY.getCodec());
        assertNull(VideoQuality.COPY.getBitrate());
    }

    @Test
    void q720pHasCorrectValues() {
        assertEquals("1280:720", VideoQuality.Q720P.getScale());
        assertEquals("libx264", VideoQuality.Q720P.getCodec());
        assertEquals("2000k", VideoQuality.Q720P.getBitrate());
    }

    @Test
    void q480pHasCorrectValues() {
        assertEquals("854:480", VideoQuality.Q480P.getScale());
        assertEquals("libx264", VideoQuality.Q480P.getCodec());
        assertEquals("1000k", VideoQuality.Q480P.getBitrate());
    }

    @Test
    void copyAudioQualityIsCopy() {
        assertEquals(AudioQuality.COPY, VideoQuality.COPY.getAudioQuality());
    }

    @Test
    void q720pAudioQualityIs192k() {
        assertEquals(AudioQuality.Q192K, VideoQuality.Q720P.getAudioQuality());
    }

    @Test
    void q480pAudioQualityIs64k() {
        assertEquals(AudioQuality.Q64K, VideoQuality.Q480P.getAudioQuality());
    }
}
