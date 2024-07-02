package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.enums.ImageType;
import app.ister.server.repository.ImageRepository;
import app.ister.server.service.ScannerHelperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageScannerTest {

    @InjectMocks
    ImageScanner subject;
    @Mock
    private ScannerHelperService scannerHelperService;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private BasicFileAttributes basicFileAttributes;

    @Test
    void analyzable() {
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/background.jpg"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/cover.png"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/cover.jpg"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/background.png"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/s01e01.jpg"), true, 0));
    }

    @Test
    void notAnalyzable() {
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/background.jpg"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/cover.png"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/cover.jpg"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/background.png"), true, 0));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/s01e01.jpg"), true, 0));
    }

    @Test
    void analyzeShowBackground() {
        ImageEntity result = (ImageEntity) subject.analyze(new DirectoryEntity(), Path.of("/disk/show/Show (2024)/background.jpg"), false, 0).orElseThrow();
        assertEquals(result.getType(), ImageType.BACKGROUND);
        assertNull(result.getSeasonEntity());
    }

    @Test
    void analyzeSeasonBackground() {
        ImageEntity result = (ImageEntity) subject.analyze(new DirectoryEntity(), Path.of("/disk/show/Show (2024)/Season 01/background.jpg"), false, 0).orElseThrow();
        assertEquals(ImageType.BACKGROUND, result.getType());
        assertNull(result.getShowEntity());
    }

    @Test
    void analyzeEpisodeBackground() {
        ImageEntity result = (ImageEntity) subject.analyze(new DirectoryEntity(), Path.of("/disk/show/Show (2024)/Season 01/s01e01-thumb.jpg"), false, 0).orElseThrow();
        assertEquals(ImageType.BACKGROUND, result.getType());
        assertNull(result.getShowEntity());
    }

    @Test
    void analyzeEmpty() {
        var result = subject.analyze(new DirectoryEntity(), Path.of("/disk/show/Show (2024)/Season 01/s01e01.mkv"), false, 0);
        assertTrue(result.isEmpty());
    }
}