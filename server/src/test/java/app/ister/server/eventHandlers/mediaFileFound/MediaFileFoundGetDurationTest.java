package app.ister.server.eventHandlers.mediaFileFound;

import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import com.github.kokorin.jaffree.ffprobe.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaFileFoundGetDurationTest {
    @Mock
    private FFmpeg ffmpegMock;

    @Mock
    private FFprobe ffprobeMock;

    @Mock
    private FFprobeResult ffprobeResultMock;

    @Mock
    private Stream streamMock;

    private MediaFileFoundGetDuration subject;

    @BeforeEach
    void setup() {
        subject = new MediaFileFoundGetDuration(ffmpegMock, ffprobeMock);
    }

    @Test
    void getDurationFromStreams() {
        when(ffprobeMock.setShowStreams(true)).thenReturn(ffprobeMock);
        when(ffprobeMock.setInput(anyString())).thenReturn(ffprobeMock);
        when(ffprobeMock.execute()).thenReturn(ffprobeResultMock);

        when(ffprobeResultMock.getStreams()).thenReturn(List.of(streamMock));

        when(streamMock.getDuration()).thenReturn(4F);
        when(streamMock.getTag("DURATION")).thenReturn("00:00:03.000000000");

        var result = subject.getDuration("src/test/resources/eventHandlers/mediaFileFound/test.mkv");
        assertEquals(4000L, result);
    }

    @Test
    void getDurationFromStreamTag() {
        when(ffprobeMock.setShowStreams(true)).thenReturn(ffprobeMock);
        when(ffprobeMock.setInput(anyString())).thenReturn(ffprobeMock);
        when(ffprobeMock.execute()).thenReturn(ffprobeResultMock);

        when(ffprobeResultMock.getStreams()).thenReturn(List.of(streamMock));

        when(streamMock.getDuration()).thenReturn(null);
        when(streamMock.getTag("DURATION")).thenReturn("00:00:03.000000000");

        var result = subject.getDuration("src/test/resources/eventHandlers/mediaFileFound/test.mkv");
        assertEquals(3000L, result);
    }

    @Test
    void getDurationWithFFmpegBecauseStreamAreEmpty() {
        when(ffprobeMock.setShowStreams(true)).thenReturn(ffprobeMock);
        when(ffprobeMock.setInput(anyString())).thenReturn(ffprobeMock);
        when(ffprobeMock.execute()).thenReturn(ffprobeResultMock);
        when(ffprobeResultMock.getStreams()).thenReturn(List.of());

        when(ffmpegMock.addInput(any())).thenReturn(ffmpegMock);
        when(ffmpegMock.addOutput(any())).thenReturn(ffmpegMock);
        when(ffmpegMock.setProgressListener(any())).thenReturn(ffmpegMock);
        var result = subject.getDuration("src/test/resources/eventHandlers/mediaFileFound/test.mkv");
        assertEquals(0L, result);
    }
}