package app.ister.server.scanner;

import app.ister.server.entitiy.*;
import app.ister.server.enums.ImageType;
import app.ister.server.repository.ImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageAnalyzerTest {

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private BasicFileAttributes basicFileAttributes;

    @InjectMocks
    ImageAnalyzer subject;

    @Test
    void analyzable() {
        when(basicFileAttributes.isRegularFile()).thenReturn(true);
        ArrayDeque<BaseEntity> analyzeStack = new ArrayDeque<>();
        analyzeStack.push(new ShowEntity());
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/background.jpg"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/cover.png"), basicFileAttributes, analyzeStack));
        analyzeStack.push(new SeasonEntity());
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/cover.jpg"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/background.png"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/s01e01.jpg"), basicFileAttributes, analyzeStack));
    }

    @Test
    void notAnalyzable() {
        when(basicFileAttributes.isRegularFile()).thenReturn(true);
        ArrayDeque<BaseEntity> analyzeStack = new ArrayDeque<>();
        analyzeStack.push(new ShowEntity());
        analyzeStack.push(new SeasonEntity());
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/background.jpg"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/cover.png"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/cover.jpg"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/background.png"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/show/Show (2024)/Season 01/s01e01.jpg"), basicFileAttributes, analyzeStack));
    }

    @Test
    void analyzeShowBackground() {
        ArrayDeque<BaseEntity> analyzeStack = new ArrayDeque<>();
        ShowEntity showEntity = new ShowEntity();
        analyzeStack.push(showEntity);
        ImageEntity result = (ImageEntity) subject.analyze(new DiskEntity(), Path.of("/disk/show/Show (2024)/background.jpg"), basicFileAttributes, analyzeStack).orElseThrow();
        assertEquals(result.getType(), ImageType.BACKGROUND);
        assertEquals(result.getShowEntity(), showEntity);
        assertNull(result.getSeasonEntity());
    }

    @Test
    void analyzeSeasonBackground() {
        ArrayDeque<BaseEntity> analyzeStack = new ArrayDeque<>();
        ShowEntity showEntity = new ShowEntity();
        SeasonEntity seasonEntity = new SeasonEntity();
        analyzeStack.push(showEntity);
        analyzeStack.push(seasonEntity);
        ImageEntity result = (ImageEntity) subject.analyze(new DiskEntity(), Path.of("/disk/show/Show (2024)/Season 01/background.jpg"), basicFileAttributes, analyzeStack).orElseThrow();
        assertEquals(ImageType.BACKGROUND, result.getType());
        assertEquals(seasonEntity, result.getSeasonEntity());
        assertNull(result.getShowEntity());
    }

    @Test
    void analyzeEmpty() {
        ArrayDeque<BaseEntity> analyzeStack = new ArrayDeque<>();
        ShowEntity showEntity = new ShowEntity();
        SeasonEntity seasonEntity = new SeasonEntity();
        analyzeStack.push(showEntity);
        analyzeStack.push(seasonEntity);
        var result = subject.analyze(new DiskEntity(), Path.of("/disk/show/Show (2024)/Season 01/s01e01.mkv"), basicFileAttributes, analyzeStack);
        assertTrue(result.isEmpty());
    }
}