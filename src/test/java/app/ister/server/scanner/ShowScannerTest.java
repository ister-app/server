package app.ister.server.scanner;

import app.ister.server.repository.ShowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShowScannerTest {
    @Mock
    private ShowRepository showRepository;

    @Mock
    private BasicFileAttributes basicFileAttributes;

    @InjectMocks
    ShowScanner subject;

    @Test
    void test() {
        when(basicFileAttributes.isDirectory()).thenReturn(true);
        var result = subject.analyzable(Path.of("/disk/shows/Show (2024)"), basicFileAttributes);
        assertTrue(result);
    }
}
