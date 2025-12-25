package app.ister.worker.scanner.scanners;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.ImageEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.ImageRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.*;

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
    @Mock
    private MessageSender messageSender;

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