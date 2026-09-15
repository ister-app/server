package app.ister.disk.events.mediafilefound;

import app.ister.core.entity.MediaFileEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = {
        "classpath:core.properties",
//        "classpath:core-local.properties"
})
class MediaFileFoundCheckForStreamsTest {
    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    @Test
    void checkMediaFileForStreams() {
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path("src/test/resources/eventHandlers/mediaFileFound/test.mkv").build();
        var result = new MediaFileFoundCheckForStreams().checkForStreams(mediaFileEntity, mediaFileEntity.getPath(), dirOfFFmpeg);
        assertEquals("vp9", result.streams().get(0).getCodecName());
    }

    /** A data stream ffprobe can't name (here: a QuickTime timecode track) must not yield a null codec name. */
    @Test
    void dataStreamWithoutCodecNameGetsPlaceholder() throws Exception {
        Path mov = Files.createTempFile("streams", ".mov");
        var ffmpeg = new ProcessBuilder(Path.of(dirOfFFmpeg, "ffmpeg").toString(), "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=64x64:rate=25", "-t", "0.2",
                "-timecode", "00:00:00:00", "-c:v", "libx264", "-pix_fmt", "yuv420p", mov.toString())
                .inheritIO().start();
        assertEquals(0, ffmpeg.waitFor());
        try {
            MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path(mov.toString()).build();
            var result = new MediaFileFoundCheckForStreams().checkForStreams(mediaFileEntity, mediaFileEntity.getPath(), dirOfFFmpeg);
            assertTrue(result.streams().size() >= 2, "expected a video and a timecode stream");
            result.streams().forEach(stream -> assertNotNull(stream.getCodecName(), "stream " + stream.getStreamIndex()));
        } finally {
            Files.deleteIfExists(mov);
        }
    }
}
