package app.ister.core.utils;

import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JaffreeTest {

    @Test
    void getFFMPEG() {
        Jaffree subject = new Jaffree();
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        FFmpeg result = subject.getFFMPEG();
        assertNotNull(result);
    }

    @Test
    void getFFPROBE() {
        Jaffree subject = new Jaffree();
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        FFprobe result = subject.getFFPROBE();
        assertNotNull(result);
    }
}
