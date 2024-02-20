package app.ister.server.scanner;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.SeasonEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.repository.ShowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodeScannerTest {
    @Mock
    private ShowRepository showRepository;

    @Mock
    private BasicFileAttributes basicFileAttributes;

    @InjectMocks
    EpisodeScanner subject;

    @Test
    void analyzable() {
        when(basicFileAttributes.isRegularFile()).thenReturn(true);
        ArrayDeque<BaseEntity> analyzeStack = new ArrayDeque<>();
        analyzeStack.push(new ShowEntity());
        analyzeStack.push(new SeasonEntity());
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/s01e01.mkv"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/shows/SHOW (2024)/s01e01.mkv"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/s02E03.mkv"), basicFileAttributes, analyzeStack));
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/s01e01.png"), basicFileAttributes, analyzeStack));
    }
}