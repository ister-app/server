package app.ister.server.events.mediafilefound;

import app.ister.server.entitiy.MediaFileEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;


@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = {
        "classpath:application.properties",
//        "classpath:application-local.properties"
})
class MediaFileFoundCheckForStreamsTest {
    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    @Test
    void checkMediaFileForStreams() {
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path("src/test/resources/eventHandlers/mediaFileFound/test.mkv").build();
        var result = new MediaFileFoundCheckForStreams().checkForStreams(mediaFileEntity, dirOfFFmpeg);
        assertEquals("vp9", result.get(0).getCodecName());
    }
}
