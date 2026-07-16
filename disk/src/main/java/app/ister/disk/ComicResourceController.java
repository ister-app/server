package app.ister.disk;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.MediaFileRepository;
import app.ister.disk.events.comicfilefound.CbzParser;
import app.ister.disk.http.ByteRanges;
import app.ister.disk.http.ByteRanges.Range;
import lombok.RequiredArgsConstructor;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Serves comic volumes to the client reader. Three shapes:
 *
 * <ul>
 *   <li>{@code /comic/{id}/manifest} — what the volume is (format, page count) and, for cbz, the
 *       ordered page list; the client picks its reader from this.</li>
 *   <li>{@code /comic/{id}/page/{index}} — one cbz page image, ETag'd and immutable-cached.</li>
 *   <li>{@code /comic/{id}/file} — the whole file with HTTP Range support; pdf.js reads PDFs in
 *       ranged chunks, and it also serves as the epub/cbz download path.</li>
 * </ul>
 *
 * <p>Auth follows the epub reader: bearer token or a {@code ?token=} stream token
 * (StreamTokenAuthenticationFilter, {@code /comic/} user path).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ComicResourceController {

    private static final Map<String, MediaType> FILE_CONTENT_TYPES = Map.of(
            "pdf", MediaType.APPLICATION_PDF,
            "cbz", MediaType.parseMediaType("application/vnd.comicbook+zip"),
            "epub", MediaType.parseMediaType("application/epub+zip"));

    private static final Map<String, MediaType> PAGE_CONTENT_TYPES = Map.of(
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "png", MediaType.IMAGE_PNG,
            "gif", MediaType.IMAGE_GIF,
            "webp", MediaType.parseMediaType("image/webp"));

    private final MediaFileRepository mediaFileRepository;
    private final CbzParser cbzParser;

    /**
     * @param index the zero-based page index (cbz only)
     * @param name  the zip entry name of the page
     * @param size  the uncompressed size in bytes
     */
    public record PageInfo(int index, String name, long size) {}

    public record ComicManifest(UUID mediaFileId, UUID bookId, String format, Integer pageCount,
                                List<PageInfo> pages) {}

    @GetMapping("/comic/{mediaFileId}/manifest")
    public ResponseEntity<ComicManifest> manifest(@PathVariable UUID mediaFileId) throws IOException {
        Optional<MediaFileEntity> mediaFile = comicMediaFile(mediaFileId);
        if (mediaFile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MediaFileEntity entity = mediaFile.get();
        Path path = Path.of(entity.getPath());
        String format = extensionOf(entity.getPath()).toUpperCase();

        List<PageInfo> pages = List.of();
        if ("CBZ".equals(format)) {
            pages = cbzPages(path);
        }
        return ResponseEntity.ok(new ComicManifest(
                entity.getId(),
                entity.getBookEntity().getId(),
                format,
                entity.getPageCount() != null ? entity.getPageCount() : (pages.isEmpty() ? null : pages.size()),
                pages));
    }

    /** One cbz page image by index, in the natural-sorted reading order of the manifest. */
    @GetMapping("/comic/{mediaFileId}/page/{index}")
    public ResponseEntity<StreamingResponseBody> page(
            @PathVariable UUID mediaFileId,
            @PathVariable int index,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        Optional<MediaFileEntity> mediaFile = comicMediaFile(mediaFileId);
        if (mediaFile.isEmpty() || !"cbz".equals(extensionOf(mediaFile.get().getPath()))) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(mediaFile.get().getPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        List<String> pages = cbzParser.pages(path);
        if (index < 0 || index >= pages.size()) {
            return ResponseEntity.notFound().build();
        }
        String entryName = pages.get(index);

        ZipFile zipFile = new ZipFile(path.toFile());
        try {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                zipFile.close();
                return ResponseEntity.notFound().build();
            }
            String etag = "\"%s-%d\"".formatted(Long.toHexString(entry.getCrc()), entry.getSize());
            if (etag.equals(ifNoneMatch)) {
                zipFile.close();
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
            }
            return ResponseEntity.ok()
                    .eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                    .contentType(PAGE_CONTENT_TYPES.getOrDefault(extensionOf(entryName), MediaType.APPLICATION_OCTET_STREAM))
                    .contentLength(entry.getSize())
                    .body(output -> {
                        try (zipFile; InputStream in = zipFile.getInputStream(entry)) {
                            in.transferTo(output);
                        }
                    });
        } catch (RuntimeException e) {
            zipFile.close();
            throw e;
        }
    }

    /** The whole volume file, with single-range support (pdf.js reads PDFs in ranged chunks). */
    @GetMapping("/comic/{mediaFileId}/file")
    public ResponseEntity<StreamingResponseBody> file(
            @PathVariable UUID mediaFileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) throws IOException {
        Optional<MediaFileEntity> mediaFile = comicMediaFile(mediaFileId);
        if (mediaFile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(mediaFile.get().getPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        long size = Files.size(path);
        Range range = ByteRanges.parseRange(rangeHeader, size);
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(range != null ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .contentType(FILE_CONTENT_TYPES.getOrDefault(extensionOf(mediaFile.get().getPath()),
                        MediaType.APPLICATION_OCTET_STREAM));
        if (range != null) {
            return response
                    .contentLength(range.length())
                    .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(range.start(), range.end(), size))
                    .body(output -> {
                        try (InputStream in = Files.newInputStream(path)) {
                            ByteRanges.skipFully(in, range.start());
                            ByteRanges.copy(in, output, range.length());
                        }
                    });
        }
        return response
                .contentLength(size)
                .body(output -> {
                    try (InputStream in = Files.newInputStream(path)) {
                        in.transferTo(output);
                    }
                });
    }

    /** A media file that is a comic volume: attached to a book and a cbz/pdf/epub. */
    private Optional<MediaFileEntity> comicMediaFile(UUID mediaFileId) {
        return mediaFileRepository.findById(mediaFileId)
                .filter(entity -> entity.getBookEntity() != null)
                .filter(entity -> FILE_CONTENT_TYPES.containsKey(extensionOf(entity.getPath())));
    }

    private List<PageInfo> cbzPages(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<String> names = cbzParser.pages(path);
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            return IntStream.range(0, names.size())
                    .mapToObj(i -> {
                        ZipEntry entry = zipFile.getEntry(names.get(i));
                        return new PageInfo(i, names.get(i), entry != null ? entry.getSize() : 0);
                    })
                    .toList();
        }
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
    }
}
