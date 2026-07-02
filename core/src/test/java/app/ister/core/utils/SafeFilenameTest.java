package app.ister.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeFilenameTest {

    @ParameterizedTest
    @ValueSource(strings = {"seg_video_720p_00001.ts", "master.m3u8", "subtitle-1.srt", "a"})
    void acceptsSafeFilenames(String filename) {
        assertEquals(filename, SafeFilename.require(filename));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "../etc/passwd", "..", "a/b.ts", "a\\b.ts", "seg%2F.ts", "a b.ts", "..hidden"})
    void rejectsUnsafeFilenames(String filename) {
        assertThrows(IllegalArgumentException.class, () -> SafeFilename.require(filename));
    }
}
