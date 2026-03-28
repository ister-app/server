package app.ister.transcoder;

import app.ister.core.utils.Jaffree;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import com.github.kokorin.jaffree.ffprobe.Format;
import com.github.kokorin.jaffree.ffprobe.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FfprobeServiceTest {

    @InjectMocks
    private FfprobeService ffprobeService;

    @Mock
    private Jaffree jaffree;

    private FFprobe ffprobe;
    private FFprobeResult ffprobeResult;

    @BeforeEach
    void setUp() {
        ffprobe = mock(FFprobe.class, RETURNS_SELF);
        ffprobeResult = mock(FFprobeResult.class);
        when(jaffree.getFFPROBE()).thenReturn(ffprobe);
        when(ffprobe.execute()).thenReturn(ffprobeResult);
    }

    @Test
    void getKeyframesFiltersNonKeyframePackets() {
        Packet keyframe = packet("K_", 0.0f);
        Packet nonKeyframe = packet("__", 1.0f);
        when(ffprobeResult.getPackets()).thenReturn(List.of(keyframe, nonKeyframe));

        List<Double> result = ffprobeService.getKeyframes("/test/video.mkv");

        assertEquals(1, result.size());
        assertEquals(0.0, result.get(0));
    }

    @Test
    void getKeyframesEnforcesTwoSecondGap() {
        // Three keyframes: 0s, 1s (too close), 3s (ok)
        Packet kf0 = packet("K_", 0.0f);
        Packet kf1 = packet("K_", 1.0f);
        Packet kf3 = packet("K_", 3.0f);
        when(ffprobeResult.getPackets()).thenReturn(List.of(kf0, kf1, kf3));

        List<Double> result = ffprobeService.getKeyframes("/test/video.mkv");

        assertEquals(2, result.size());
        assertEquals(0.0, result.get(0));
        assertEquals(3.0, result.get(1));
    }

    @Test
    void getKeyframesExactlyTwoSecondsApartIsIncluded() {
        Packet kf0 = packet("K_", 0.0f);
        Packet kf2 = packet("K_", 2.0f);
        when(ffprobeResult.getPackets()).thenReturn(List.of(kf0, kf2));

        List<Double> result = ffprobeService.getKeyframes("/test/video.mkv");

        assertEquals(2, result.size());
        assertEquals(2.0, result.get(1));
    }

    @Test
    void getKeyframesSkipsPacketsWithNullFlags() {
        Packet nullFlags = packet(null, 0.0f);
        Packet validKf = packet("K_", 5.0f);
        when(ffprobeResult.getPackets()).thenReturn(List.of(nullFlags, validKf));

        List<Double> result = ffprobeService.getKeyframes("/test/video.mkv");

        assertEquals(1, result.size());
        assertEquals(5.0, result.get(0));
    }

    @Test
    void getKeyframesSkipsPacketsWithNullPtsTime() {
        Packet nullPts = packet("K_", null);
        Packet validKf = packet("K_", 3.0f);
        when(ffprobeResult.getPackets()).thenReturn(List.of(nullPts, validKf));

        List<Double> result = ffprobeService.getKeyframes("/test/video.mkv");

        assertEquals(1, result.size());
        assertEquals(3.0, result.get(0));
    }

    @Test
    void getKeyframesReturnsEmptyListWhenNoPackets() {
        when(ffprobeResult.getPackets()).thenReturn(List.of());

        List<Double> result = ffprobeService.getKeyframes("/test/video.mkv");

        assertTrue(result.isEmpty());
    }

    @Test
    void getTotalDurationReturnsFormatDuration() {
        Format format = mock(Format.class);
        when(ffprobeResult.getFormat()).thenReturn(format);
        when(format.getDuration()).thenReturn(3723.5f);

        double result = ffprobeService.getTotalDuration("/test/video.mkv");

        assertEquals(3723.5, result, 0.001);
    }

    @Test
    void getTotalDurationReturnsZeroWhenNullDuration() {
        Format format = mock(Format.class);
        when(ffprobeResult.getFormat()).thenReturn(format);
        when(format.getDuration()).thenReturn(null);

        double result = ffprobeService.getTotalDuration("/test/video.mkv");

        assertEquals(0.0, result);
    }

    private Packet packet(String flags, Float ptsTime) {
        Packet p = mock(Packet.class);
        when(p.getFlags()).thenReturn(flags);
        when(p.getPtsTime()).thenReturn(ptsTime);
        return p;
    }
}
