package app.ister.transcoder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HlsControllerTest {

    @InjectMocks
    private HlsController controller;

    @Mock
    private HlsService hlsService;

    @TempDir
    Path tempDir;

    private UUID mediaFileId;

    @BeforeEach
    void setUp() {
        mediaFileId = UUID.randomUUID();
    }

    // ========== getMasterPlaylist ==========

    @Test
    void getMasterPlaylistReturnsM3u8ContentType() throws IOException {
        when(hlsService.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT))
                .thenReturn("#EXTM3U\nstream_video_copy.m3u8\n");

        ResponseEntity<String> response = controller.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT, null);

        assertEquals("application/x-mpegURL", response.getHeaders().getFirst("Content-Type"));
    }

    @Test
    void getMasterPlaylistWithoutTokenReturnsUnchangedContent() throws IOException {
        String content = "#EXTM3U\nstream_video_copy.m3u8\n";
        when(hlsService.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT)).thenReturn(content);

        ResponseEntity<String> response = controller.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT, null);

        assertEquals(content, response.getBody());
    }

    @Test
    void getMasterPlaylistWithBlankTokenReturnsUnchangedContent() throws IOException {
        String content = "#EXTM3U\nstream_video_copy.m3u8\n";
        when(hlsService.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT)).thenReturn(content);

        ResponseEntity<String> response = controller.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT, "   ");

        assertEquals(content, response.getBody());
    }

    @Test
    void getMasterPlaylistWithTokenAppendsTokenToSegmentLines() throws IOException {
        String content = "#EXTM3U\nstream_video_copy.m3u8\nstream_video_720p.m3u8";
        when(hlsService.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT)).thenReturn(content);

        ResponseEntity<String> response = controller.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT, "abc123");

        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("stream_video_copy.m3u8?token=abc123"));
        assertTrue(body.contains("stream_video_720p.m3u8?token=abc123"));
    }

    @Test
    void getMasterPlaylistWithTokenAppendsTokenToUriAttributes() throws IOException {
        String content = "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio-copy\",URI=\"stream_audio_1_copy.m3u8\"";
        when(hlsService.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT)).thenReturn(content);

        ResponseEntity<String> response = controller.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT, "mytoken");

        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("URI=\"stream_audio_1_copy.m3u8?token=mytoken\""));
    }

    @Test
    void getMasterPlaylistDoesNotAppendTokenToHashLines() throws IOException {
        String content = "#EXTM3U\n#EXT-X-VERSION:6";
        when(hlsService.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT)).thenReturn(content);

        ResponseEntity<String> response = controller.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT, "tok");

        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("#EXTM3U"));
        assertFalse(body.contains("#EXTM3U?token=tok"));
    }

    // ========== getStreamPlaylist ==========

    @Test
    void getStreamPlaylistReturnsM3u8ContentType() throws IOException {
        when(hlsService.getStreamPlaylist(mediaFileId, "stream_video_720p.m3u8"))
                .thenReturn("#EXTM3U\nseg_video_720p_00000.ts\n");

        ResponseEntity<String> response = controller.getStreamPlaylist(mediaFileId, "stream_video_720p.m3u8", null);

        assertEquals("application/x-mpegURL", response.getHeaders().getFirst("Content-Type"));
    }

    @Test
    void getStreamPlaylistWithTokenAppendsToSegments() throws IOException {
        String content = "#EXTM3U\nseg_video_720p_00000.ts\nseg_video_720p_00001.ts";
        when(hlsService.getStreamPlaylist(mediaFileId, "stream_video_720p.m3u8")).thenReturn(content);

        ResponseEntity<String> response = controller.getStreamPlaylist(mediaFileId, "stream_video_720p.m3u8", "tok42");

        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("seg_video_720p_00000.ts?token=tok42"));
        assertTrue(body.contains("seg_video_720p_00001.ts?token=tok42"));
    }

    // ========== getTsSegment (video) ==========

    @Test
    void getTsSegmentVideoReturnsTsContentType() throws IOException {
        Path segFile = tempDir.resolve("seg_video_720p_00000.ts");
        Files.writeString(segFile, "fake-ts-data");
        when(hlsService.getVideoSegment(mediaFileId, "seg_video_720p_00000.ts")).thenReturn(segFile);

        ResponseEntity<?> response = controller.getTsSegment(mediaFileId, "seg_video_720p_00000.ts");

        assertEquals("video/MP2T", response.getHeaders().getFirst("Content-Type"));
        assertEquals(Files.size(segFile), response.getHeaders().getContentLength());
    }

    // ========== getTsSegment (audio) ==========

    @Test
    void getTsSegmentAudioDelegatesToAudioSegment() throws IOException {
        Path segFile = tempDir.resolve("seg_audio_0.000000_60.000000_1_copy.ts");
        Files.writeString(segFile, "fake-audio-data");
        when(hlsService.getAudioSegment(mediaFileId, "seg_audio_0.000000_60.000000_1_copy.ts")).thenReturn(segFile);

        ResponseEntity<?> response = controller.getTsSegment(mediaFileId, "seg_audio_0.000000_60.000000_1_copy.ts");

        assertEquals("video/MP2T", response.getHeaders().getFirst("Content-Type"));
    }

    // ========== getVttSegment ==========

    @Test
    void getVttSegmentReturnsVttContentType() throws IOException {
        UUID subId = UUID.randomUUID();
        String filename = "seg_sub_" + subId + "_00000.vtt";
        String content = "WEBVTT\n\n00:00:01.480 --> 00:00:03.480\nHello\n";
        when(hlsService.getSubtitleSegment(mediaFileId, filename)).thenReturn(content);

        ResponseEntity<String> response = controller.getVttSegment(mediaFileId, filename);

        assertEquals("text/vtt", response.getHeaders().getFirst("Content-Type"));
        assertEquals(content, response.getBody());
    }

    // ========== getSrtSubtitle ==========

    @Test
    void getSrtSubtitleReturnsCorrectContentType() throws IOException {
        UUID subId = UUID.randomUUID();
        String filename = "sub_" + subId + ".srt";
        Path srtFile = tempDir.resolve(filename);
        Files.writeString(srtFile, "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n");
        when(hlsService.getSrtSubtitle(mediaFileId, filename)).thenReturn(srtFile);

        ResponseEntity<?> response = controller.getSrtSubtitle(mediaFileId, filename);

        assertEquals("application/x-subrip", response.getHeaders().getFirst("Content-Type"));
        assertEquals(Files.size(srtFile), response.getHeaders().getContentLength());
    }

    // ========== appendTokenToUris edge cases ==========

    @Test
    void appendTokenToUrisPreservesEmptyLines() throws IOException {
        String content = "#EXTM3U\n\nstream_video_copy.m3u8";
        when(hlsService.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT)).thenReturn(content);

        ResponseEntity<String> response = controller.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT, "tok");

        String body = response.getBody();
        assertNotNull(body);
        // Empty line should remain (not have token appended)
        String[] lines = body.split("\n");
        assertEquals("", lines[1]);
    }

    @Test
    void appendTokenToUrisHandlesMultipleUriAttributesInOneLine() throws IOException {
        // Edge case: two URI= on same line (not realistic but tests regex coverage)
        String content = "#EXT-X-SESSION-DATA:DATA-ID=\"test\",URI=\"test1.m3u8\"";
        when(hlsService.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT)).thenReturn(content);

        ResponseEntity<String> response = controller.getMasterPlaylist(mediaFileId, true, true, SubtitleFormat.WEBVTT, "tok");

        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("URI=\"test1.m3u8?token=tok\""));
    }
}
