package app.ister.server.eventHandlers.mediaFileFound;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = {
        "classpath:application.properties",
//        "classpath:application-local.properties"
})
class MediaFileFoundCreateBackgroundTest {
    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    @AfterEach
    void after() throws IOException {
        Files.deleteIfExists(Path.of("item.jpg"));
    }

    @Test
    void createBackground() {
        URL resourceAsStream = MediaFileFoundCreateBackgroundTest.class.getResource("/eventHandlers/mediaFileFound/test.mkv");

        Path toPath = Path.of("item.jpg");
        MediaFileFoundCreateBackground.createBackground(toPath, Path.of(resourceAsStream.getPath()), dirOfFFmpeg, 1000);

        assertTrue(Files.isRegularFile(toPath));
    }
}