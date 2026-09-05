package app.ister.transcoder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Goes through the message converters rather than calling the controller directly, because what
 * matters here is the response that reaches the wire.
 * <p>
 * ffmpeg treats a segment connection as non-seekable unless it sees {@code Accept-Ranges: bytes};
 * a known Content-Length is not enough. On a non-seekable connection it reconnects after a network
 * error at offset 0 instead of the current one and replays the segment, which shows up as the
 * picture jumping back a few seconds while the audio rendition plays on.
 */
@ExtendWith(MockitoExtension.class)
class HlsControllerRangeTest {

    @InjectMocks
    private HlsController controller;

    @Mock
    private HlsService hlsService;

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private UUID mediaFileId;
    private Path segment;

    private static final String SEGMENT_NAME = "seg_video_copy_00000.ts";
    private static final String SEGMENT_BODY = "0123456789abcdef";

    @BeforeEach
    void setUp() throws IOException {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        mediaFileId = UUID.randomUUID();
        segment = tempDir.resolve(SEGMENT_NAME);
        Files.writeString(segment, SEGMENT_BODY);
    }

    @Test
    void segmentResponseAdvertisesRangeSupport() throws Exception {
        when(hlsService.getVideoSegment(mediaFileId, SEGMENT_NAME)).thenReturn(segment);

        mockMvc.perform(get("/hls/{id}/{name}", mediaFileId, SEGMENT_NAME))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, SEGMENT_BODY.length()))
                .andExpect(content().string(SEGMENT_BODY));
    }

    @Test
    void segmentServesTheRequestedRange() throws Exception {
        when(hlsService.getVideoSegment(mediaFileId, SEGMENT_NAME)).thenReturn(segment);

        mockMvc.perform(get("/hls/{id}/{name}", mediaFileId, SEGMENT_NAME)
                        .header(HttpHeaders.RANGE, "bytes=6-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 6-15/16"))
                .andExpect(content().string("6789abcdef"));
    }

    @Test
    void subtitleResponseAdvertisesRangeSupport() throws Exception {
        Path srt = tempDir.resolve("subs.srt");
        Files.writeString(srt, "1\n00:00:00,000 --> 00:00:01,000\nhoi\n");
        when(hlsService.getSrtSubtitle(mediaFileId, "subs.srt")).thenReturn(srt);

        mockMvc.perform(get("/hls/{id}/{name}", mediaFileId, "subs.srt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));
    }
}
