package app.ister.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LibraryPathValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Show (2019)/Season 01/Show - s01e01.mkv",
            "J.K. Rowling/Harry Potter (1997)/001_Chapter.mp3",
            "R.E.M./Out of Time (1991)/01 - Radio Song.flac",
            "Therapy?/Troublegum/cover.jpg",
            "Amélie (2001).mkv"})
    void acceptsRealMediaPaths(String path) {
        assertEquals(path, LibraryPathValidator.requireRelative(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../etc/passwd", "a/../../b", "a/./b", "/abs/path.mkv", "a\\b.mkv", "a//b.mkv",
            ".hidden/file.mkv", "Show/.ister-upload/x.part", "a/b\u0000.mkv", "a/b\n.mkv",
            " leading/file.mkv", "trailing /file.mkv", "Show/NUL.mkv", "con/file.mkv"})
    void rejectsUnsafePaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> LibraryPathValidator.requireRelative(path));
    }

    @Test
    void rejectsEmptyWhenRequired() {
        assertThrows(IllegalArgumentException.class, () -> LibraryPathValidator.requireRelative(""));
        assertThrows(IllegalArgumentException.class, () -> LibraryPathValidator.requireRelative(null));
    }

    @Test
    void emptyOptionalPathIsTheRoot() {
        assertEquals("", LibraryPathValidator.optionalRelative(null));
        assertEquals("", LibraryPathValidator.optionalRelative("  "));
        assertEquals("Artist", LibraryPathValidator.optionalRelative("Artist/"));
    }

    @Test
    void normalizesToNfc() {
        // "e" + combining acute accent → precomposed "é"
        assertEquals("Amélie", LibraryPathValidator.requireSegment("Amélie"));
    }

    @Test
    void rejectsOverlongSegment() {
        assertThrows(IllegalArgumentException.class, () -> LibraryPathValidator.requireSegment("é".repeat(128)));
    }

    @Test
    void resolvesUnderLocalAndS3Directories() {
        assertEquals("/media/shows/Show (2019)/s01e01.mkv",
                LibraryPathValidator.resolve("/media/shows", "Show (2019)/s01e01.mkv"));
        assertEquals("s3://bucket/shows/Show (2019)/s01e01.mkv",
                LibraryPathValidator.resolve("s3://bucket/shows", "Show (2019)/s01e01.mkv"));
        assertThrows(IllegalArgumentException.class, () -> LibraryPathValidator.resolve("/media/shows", "../movies/x.mkv"));
    }
}
