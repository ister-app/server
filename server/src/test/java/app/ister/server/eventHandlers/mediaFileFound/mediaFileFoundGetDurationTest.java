package app.ister.server.eventHandlers.mediaFileFound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = {
        "classpath:application.properties",
//        "classpath:application-local.properties"
})
class mediaFileFoundGetDurationTest {
    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    @Test
    void getDuration() {
        var result = MediaFileFoundGetDuration.getDuration("src/test/resources/eventHandlers/mediaFileFound/test.mkv", dirOfFFmpeg);
        assertEquals(3001L, result);

    }
}