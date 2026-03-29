package app.ister.transcoder;

import app.ister.core.enums.SubtitleFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class HlsController {

    private static final String M3U8_CONTENT_TYPE = "application/x-mpegURL";
    private static final String TS_CONTENT_TYPE = "video/MP2T";
    private static final String VTT_CONTENT_TYPE = "text/vtt";
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
        String content = hlsService.getStreamPlaylist(mediaFileId, streamFilename);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, M3U8_CONTENT_TYPE)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_2H)
                .body(appendTokenToUris(content, token));
    }

    @GetMapping("/hls/{mediaFileId}/{segmentFilename:.+\\.ts}")
    public ResponseEntity<InputStreamResource> getTsSegment(
            @PathVariable UUID mediaFileId,
            @PathVariable String segmentFilename) throws IOException {
        Path filePath;
        if (segmentFilename.startsWith("seg_video_")) {
            filePath = hlsService.getVideoSegment(mediaFileId, segmentFilename);
        } else {
            filePath = hlsService.getAudioSegment(mediaFileId, segmentFilename);
        }
        long size = Files.size(filePath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, TS_CONTENT_TYPE)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_2H)
                .contentLength(size)
                .body(new InputStreamResource(new FileInputStream(filePath.toFile())));
    }

    @GetMapping("/hls/{mediaFileId}/{segmentFilename:.+\\.vtt}")
    public ResponseEntity<String> getVttSegment(
            @PathVariable UUID mediaFileId,
            @PathVariable String segmentFilename) throws IOException {
        String content = hlsService.getSubtitleSegment(mediaFileId, segmentFilename);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, VTT_CONTENT_TYPE)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_2H)
                .body(content);
    }

    @GetMapping("/hls/{mediaFileId}/{filename:.+\\.srt}")
    public ResponseEntity<InputStreamResource> getSrtSubtitle(
            @PathVariable UUID mediaFileId,
            @PathVariable String filename) throws IOException {
        Path filePath = hlsService.getSrtSubtitle(mediaFileId, filename);
        long size = Files.size(filePath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/x-subrip")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_2H)
                .contentLength(size)
                .body(new InputStreamResource(new FileInputStream(filePath.toFile())));
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
