package app.ister.server.scanner.scanners;

import app.ister.server.repository.ShowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class MediaFileScannerTest {
    @InjectMocks
    MediaFileScanner subject;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private BasicFileAttributes basicFileAttributes;

    @Test
    void analyzable() {
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/s01e01.mkv"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/shows/SHOW (2024)/s01e01.mkv"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/s02E03.mkv"), true, 0));
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/s01e01.png"), true, 0));
    }
}