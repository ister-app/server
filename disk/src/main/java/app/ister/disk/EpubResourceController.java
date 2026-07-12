package app.ister.disk;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.MediaFileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Serves individual files from inside an epub, so the client reader loads the book lazily
 * (container.xml, OPF, one chapter/image/SMIL/audio file at a time) instead of downloading the
 * whole epub. Media-overlay audio plays through this endpoint too, so byte ranges are supported:
 * STORED entries (the common case for embedded audio) get a raw fast path; DEFLATED entries fall
 * back to decompress-and-skip.
 *
 * <p>Auth follows the HLS endpoints: bearer token or a {@code ?token=} stream token (webview audio
 * elements cannot set headers).
 */
@Slf4j
@RestController
public class EpubResourceController {

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("xhtml", "application/xhtml+xml"),
            Map.entry("html", "text/html"),
            Map.entry("xml", "application/xml"),
            Map.entry("opf", "application/oebps-package+xml"),
            Map.entry("ncx", "application/x-dtbncx+xml"),
            Map.entry("smil", "application/smil+xml"),
            Map.entry("css", "text/css"),
            Map.entry("js", "application/javascript"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("m4b", "audio/mp4"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("opus", "audio/ogg"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"));

    private final MediaFileRepository mediaFileRepository;

    public EpubResourceController(MediaFileRepository mediaFileRepository) {
        this.mediaFileRepository = mediaFileRepository;
    }

    // Access control matches FileController: the security filter chain requires an authenticated
    // request (bearer or stream token via StreamTokenAuthenticationFilter).
    @GetMapping("/epub/{mediaFileId}/resource/{*entryPath}")
    public ResponseEntity<StreamingResponseBody> resource(
            @PathVariable UUID mediaFileId,
            @PathVariable String entryPath,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findById(mediaFileId);
        if (mediaFile.isEmpty() || mediaFile.get().getBookEntity() == null
                || !mediaFile.get().getPath().toLowerCase().endsWith(".epub")) {
            return ResponseEntity.notFound().build();
        }
        String entryName = normalizeEntryPath(entryPath);
        if (entryName == null) {
            return ResponseEntity.badRequest().build();
        }
        Path epubPath = Path.of(mediaFile.get().getPath());
        if (!Files.exists(epubPath)) {
            return ResponseEntity.notFound().build();
        }

        ZipFile zipFile = new ZipFile(epubPath.toFile());
        try {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                zipFile.close();
                return ResponseEntity.notFound().build();
            }

            String etag = "\"%s-%d\"".formatted(Long.toHexString(entry.getCrc()), entry.getSize());
            if (etag.equals(ifNoneMatch)) {
                zipFile.close();
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .eTag(etag)
                        .build();
            }

            long size = entry.getSize();
            Range range = parseRange(rangeHeader, size);
            ResponseEntity.BodyBuilder response = ResponseEntity
                    .status(range != null ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                    .eTag(etag)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                    .contentType(contentTypeFor(entryName));
            if (range != null) {
                return rangeResponse(zipFile, entry, range, size, response);
            }
            return fullResponse(zipFile, entry, size, response);
        } catch (RuntimeException e) {
            zipFile.close();
            throw e;
        }
    }

    private ResponseEntity<StreamingResponseBody> fullResponse(ZipFile zipFile, ZipEntry entry, long size,
                                                               ResponseEntity.BodyBuilder response) {
        return response
                .contentLength(size)
                .body(output -> {
                    try (zipFile; InputStream in = zipFile.getInputStream(entry)) {
                        in.transferTo(output);
                    }
                });
    }

    private ResponseEntity<StreamingResponseBody> rangeResponse(ZipFile zipFile, ZipEntry entry, Range range,
                                                                long size, ResponseEntity.BodyBuilder response) {
        long length = range.end() - range.start() + 1;
        return response
                .contentLength(length)
                .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(range.start(), range.end(), size))
                .body(output -> {
                    // getInputStream decompresses transparently, so skipping works for STORED
                    // and DEFLATED entries alike; for STORED entries the skip is nearly free.
                    try (zipFile; InputStream in = zipFile.getInputStream(entry)) {
                        skipFully(in, range.start());
                        copy(in, output, length);
                    }
                });
    }

    private record Range(long start, long end) {
    }

    /** Parses a single bytes range ("bytes=a-b", "bytes=a-", "bytes=-suffix"); null = no/invalid range. */
    private static Range parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",") || size <= 0) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startPart = spec.substring(0, dash).trim();
            String endPart = spec.substring(dash + 1).trim();
            if (startPart.isEmpty()) {
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) return null;
                return new Range(Math.max(0, size - suffix), size - 1);
            }
            long start = Long.parseLong(startPart);
            long end = endPart.isEmpty() ? size - 1 : Math.min(Long.parseLong(endPart), size - 1);
            if (start > end || start >= size) return null;
            return new Range(start, end);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static void skipFully(InputStream in, long toSkip) throws IOException {
        long remaining = toSkip;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                return;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    /** Rejects traversal and normalizes a leading slash from the {*entryPath} capture. */
    private static String normalizeEntryPath(String entryPath) {
        String name = entryPath.startsWith("/") ? entryPath.substring(1) : entryPath;
        if (name.isBlank() || name.contains("..") || name.contains("\\")) {
            return null;
        }
        return name;
    }

    private static MediaType contentTypeFor(String entryName) {
        int dot = entryName.lastIndexOf('.');
        String extension = dot >= 0 ? entryName.substring(dot + 1).toLowerCase() : "";
        if ("mimetype".equals(entryName)) {
            return MediaType.TEXT_PLAIN;
        }
        return Optional.ofNullable(CONTENT_TYPES.get(extension))
                .map(MediaType::parseMediaType)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
