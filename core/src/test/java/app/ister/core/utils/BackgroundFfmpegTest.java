package app.ister.core.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundFfmpegTest {

    @TempDir
    Path tempDir;

    private final BackgroundFfmpeg subject = new BackgroundFfmpeg();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(subject, "backgroundNice", 10);
        ReflectionTestUtils.setField(subject, "nicePath", "/usr/bin/nice");
        ReflectionTestUtils.setField(subject, "ffmpegDir", "/usr/bin");
        ReflectionTestUtils.setField(subject, "tmpDir", tempDir.toString());
    }

    @Test
    void writesExecutableWrappersForFfmpegAndFfprobe() throws IOException {
        Path dir = subject.createWrappers();

        assertNotNull(dir, "nice is available on Linux, wrappers should be created");
        assertEquals(tempDir.resolve(BackgroundFfmpeg.WRAPPER_DIR_NAME), dir);
        for (String binary : new String[]{"ffmpeg", "ffprobe"}) {
            Path script = dir.resolve(binary);
            assertTrue(Files.isExecutable(script), binary);
            String content = Files.readString(script);
            assertTrue(content.contains("nice -n 10"), binary);
            assertTrue(content.contains("/usr/bin/" + binary), binary);
        }
    }

    @Test
    void dirIsTheWrapperOnceInitialisedAndTheRealDirectoryOtherwise() {
        assertEquals("/usr/bin", subject.dir());
        subject.init();
        assertEquals(tempDir.resolve(BackgroundFfmpeg.WRAPPER_DIR_NAME).toString(), subject.dir());
        assertNotNull(subject.wrapperDir());
    }

    @Test
    void disabledWhenNiceIsZero() {
        ReflectionTestUtils.setField(subject, "backgroundNice", 0);
        assertNull(subject.createWrappers());
    }

    @Test
    void disabledWhenTheNiceProbeFails() {
        ReflectionTestUtils.setField(subject, "nicePath", "/bin/false");
        assertNull(subject.createWrappers());
    }

    @Test
    void disabledWhenTheNiceBinaryIsMissing() {
        ReflectionTestUtils.setField(subject, "nicePath", tempDir.resolve("no-such-nice").toString());
        subject.init();
        assertNull(subject.wrapperDir());
        assertEquals("/usr/bin", subject.dir());
    }
}
