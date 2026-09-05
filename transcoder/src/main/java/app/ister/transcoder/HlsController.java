package app.ister.transcoder;

import app.ister.core.enums.SubtitleFormat;
import app.ister.core.utils.SafeFilename;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class HlsController {

    private static final String M3U8_CONTENT_TYPE = "application/x-mpegURL";
    private static final String TS_CONTENT_TYPE = "video/MP2T";
    private static final String VTT_CONTENT_TYPE = "text/vtt;charset=utf-8";
    private static final String CACHE_CONTROL_2H = "public, max-age=7200";

    private final HlsService hlsService;

    /**
     * @param direct    include the stream-copy (direct) quality variant (default: true)
     * @param transcode include the re-encoded 720p and 480p quality variants (default: true)
     * @param token     stream token for authentication (appended to all URIs in the playlist)
     */
    @GetMapping("/hls/{mediaFileId}/master.m3u8")
    public ResponseEntity<String> getMasterPlaylist(
            @PathVariable UUID mediaFileId,
            @RequestParam(defaultValue = "true") boolean direct,
            @RequestParam(defaultValue = "true") boolean transcode,
            @RequestParam(defaultValue = "WEBVTT") SubtitleFormat subtitleFormat,
            @RequestParam(required = false) String token) throws IOException {
        String content = hlsService.getMasterPlaylist(mediaFileId, direct, transcode, subtitleFormat);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, M3U8_CONTENT_TYPE)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_2H)
                .body(appendTokenToUris(content, token));
    }

    @GetMapping("/hls/{mediaFileId}/{streamFilename:.+\\.m3u8}")
    public ResponseEntity<String> getStreamPlaylist(
            @PathVariable UUID mediaFileId,
            @PathVariable String streamFilename,
            @RequestParam(required = false) String token) throws IOException {
        String content = hlsService.getStreamPlaylist(mediaFileId, SafeFilename.require(streamFilename));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, M3U8_CONTENT_TYPE)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_2H)
                .body(appendTokenToUris(content, token));
    }

    /**
     * Segments are returned as a {@link PathResource} rather than an
     * {@code InputStreamResource} on purpose. Spring skips range handling for the latter
     * (see {@code AbstractMessageConverterMethodProcessor#isResourceType}), so the response
     * carried no {@code Accept-Ranges} header and answered every {@code Range} request with the
     * whole body.
     * <p>
     * ffmpeg reads that as a non-seekable connection — it only clears {@code is_streamed} when it
     * sees {@code Accept-Ranges: bytes}, a known length is not enough. Reconnecting after a
     * network error mid-segment then restarts the segment at offset <em>0</em> instead of the
     * current one ({@code http_read_stream}: {@code target = h->is_streamed ? 0 : s->off}) and
     * feeds those bytes to the demuxer a second time. The picture jumps back a few seconds and
     * replays while the audio rendition, on its own connection, runs on undisturbed.
     * <p>
     * With a real file resource Spring advertises {@code Accept-Ranges: bytes} and serves 206
     * responses, so a reconnect resumes where it left off.
     */
    @GetMapping("/hls/{mediaFileId}/{segmentFilename:.+\\.ts}")
    public ResponseEntity<Resource> getTsSegment(
            @PathVariable UUID mediaFileId,
            @PathVariable String segmentFilename) throws IOException {
        SafeFilename.require(segmentFilename);
        Path filePath;
        if (segmentFilename.startsWith("seg_video_")) {
            filePath = hlsService.getVideoSegment(mediaFileId, segmentFilename);
        } else {
            filePath = hlsService.getAudioSegment(mediaFileId, segmentFilename);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, TS_CONTENT_TYPE)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_2H)
                .body(new PathResource(filePath));
    }

    @GetMapping("/hls/{mediaFileId}/{segmentFilename:.+\\.vtt}")
    public ResponseEntity<String> getVttSegment(
            @PathVariable UUID mediaFileId,
            @PathVariable String segmentFilename) throws IOException {
        String content = hlsService.getSubtitleSegment(mediaFileId, SafeFilename.require(segmentFilename));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, VTT_CONTENT_TYPE)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_2H)
                .body(content);
    }

    @GetMapping("/hls/{mediaFileId}/{filename:.+\\.srt}")
    public ResponseEntity<Resource> getSrtSubtitle(
            @PathVariable UUID mediaFileId,
            @PathVariable String filename) throws IOException {
        Path filePath = hlsService.getSrtSubtitle(mediaFileId, SafeFilename.require(filename));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/x-subrip")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_2H)
                .body(new PathResource(filePath));
    }

    /**
     * Appends {@code ?token=<token>} to every URI in an M3U8 playlist:
     * - Standalone URI lines (segment filenames)
     * - {@code URI="..."} attributes embedded in #EXT-X-MEDIA tags
     * The cached playlist on disk stays clean; the token is only injected in the HTTP response.
     */
    private String appendTokenToUris(String content, String token) {
        if (token == null || token.isBlank()) {
            return content;
        }
        return Arrays.stream(content.split("\n"))
                .map(line -> {
                    if (line.isBlank()) return line;
                    if (line.startsWith("#")) {
                        // Inject token into URI="..." attributes (e.g. EXT-X-MEDIA, EXT-X-SESSION-DATA)
                        return line.replaceAll("URI=\"([^\"]+)\"", "URI=\"$1?token=" + token + "\"");
                    }
                    // Standalone segment/playlist filename
                    return line + "?token=" + token;
                })
                .collect(Collectors.joining("\n"));
    }
}
