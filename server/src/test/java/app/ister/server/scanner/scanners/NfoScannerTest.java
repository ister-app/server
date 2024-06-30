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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NfoScannerTest {
    @InjectMocks
    NfoScanner subject;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private BasicFileAttributes basicFileAttributes;

    @Test
    void analyzable() {
        when(basicFileAttributes.isRegularFile()).thenReturn(true);
        assertTrue(subject.analyzable(Path.of("/disk/shows/SHOW (2024)/tvshow.nfo"), basicFileAttributes));
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01/s01e01.nfo"), basicFileAttributes));
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01/tvshow.nfo"), basicFileAttributes));
    }
}