package app.ister.server.scanner;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.repository.SeasonRepository;
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
class SeasonAnalyzerTest {

    @Mock
    private SeasonRepository seasonRepository;

    @Mock
    private BasicFileAttributes basicFileAttributes;

    @InjectMocks
    SeasonAnalyzer subject;

    @Test
    void analyzable() {
        when(basicFileAttributes.isDirectory()).thenReturn(true);
        ArrayDeque<BaseEntity> analyzeStack = new ArrayDeque<>();
        analyzeStack.add(new ShowEntity());
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/season 01"), basicFileAttributes, analyzeStack));
        assertTrue(subject.analyzable(Path.of("/disk/shows/Show (2024)/SeaSoN 01"), basicFileAttributes, analyzeStack));
    }

    @Test
    void analyzableReturnsFalse() {
        when(basicFileAttributes.isDirectory()).thenReturn(true);
        ArrayDeque<BaseEntity> analyzeStack = new ArrayDeque<>();
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01"), basicFileAttributes, analyzeStack));
        analyzeStack.add(new ShowEntity());
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)"), basicFileAttributes, analyzeStack));
        when(basicFileAttributes.isDirectory()).thenReturn(false);
        assertFalse(subject.analyzable(Path.of("/disk/shows/Show (2024)/Season 01"), basicFileAttributes, analyzeStack));
    }
}