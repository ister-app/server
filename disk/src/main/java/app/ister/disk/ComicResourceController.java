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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
 *   <li>{@code /comic/{id}/page/{index}} — one page image (a cbz entry, or a pdf page rasterized
 *       server-side), ETag'd and immutable-cached.</li>
 *   <li>{@code /comic/{id}/file} — the whole file with HTTP Range support; the epub/cbz download
 *       path, and what pre-page-rendering clients used to read PDFs in ranged chunks.</li>
 * </ul>
 *
 * <p>Auth follows the epub reader: bearer token or a {@code ?token=} stream token
 * (StreamTokenAuthenticationFilter, {@code /comic/} user path).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ComicResourceController {

    private static final String CACHE_CONTROL_IMMUTABLE = "private, max-age=31536000, immutable";

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

    /** Render width for a pdf page requested without {@code ?width=} — the full-resolution
     *  read/download size, matching what the old client-side pdfium renderer used. */
    private static final int DEFAULT_PDF_WIDTH = 1600;

    private final MediaFileRepository mediaFileRepository;
    private final CbzParser cbzParser;
    private final PdfPageCache pdfPageCache;

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
        Integer pageCount = entity.getPageCount();
        if (pageCount == null && !pages.isEmpty()) {
            pageCount = pages.size();
        }
        return ResponseEntity.ok(new ComicManifest(
                entity.getId(),
                entity.getBookEntity().getId(),
                format,
                pageCount,
                pages));
    }

    /**
     * One page image by index: a cbz entry in the natural-sorted reading order of the manifest,
     * or a pdf page rasterized server-side (cached under the tmp dir, see {@link PdfPageCache}).
     *
     * <p>With {@code ?width=} the image is downscaled server-side (for thumbnail strips); the
     * requested width is bucketed to {@link #WIDTH_BUCKETS} so the immutable cache stays bounded.
     * For cbz, scaling is best-effort: when decoding or AWT is unavailable the original page is
     * served. A pdf page has no original bytes to fall back on, so a failed render is a 500 —
     * a 404 would let clients cache "this page does not exist".
     */
    @GetMapping("/comic/{mediaFileId}/page/{index}")
    public ResponseEntity<StreamingResponseBody> page(
            @PathVariable UUID mediaFileId,
            @PathVariable int index,
            @RequestParam(required = false) Integer width,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        Optional<MediaFileEntity> mediaFile = comicMediaFile(mediaFileId);
        if (mediaFile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String extension = extensionOf(mediaFile.get().getPath());
        Path path = Path.of(mediaFile.get().getPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        if ("pdf".equals(extension)) {
            return pdfPage(mediaFile.get(), path, index, width, ifNoneMatch);
        }
        if (!"cbz".equals(extension)) {
            return ResponseEntity.notFound().build();
        }
        List<String> pages = cbzParser.pages(path);
        if (index < 0 || index >= pages.size()) {
            return ResponseEntity.notFound().build();
        }
        String entryName = pages.get(index);
        Integer targetWidth = bucketWidth(width);

        // Pages are small (at most a few MB), so buffering the entry keeps the ZipFile inside a
        // plain try-with-resources. Streaming it would mean closing the zip in the response-body
        // lambda — leaked whenever Spring never runs that lambda (client gone before writing).
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                return ResponseEntity.notFound().build();
            }
            String etag = targetWidth == null
                    ? "\"%s-%d\"".formatted(Long.toHexString(entry.getCrc()), entry.getSize())
                    : "\"%s-%d-w%d\"".formatted(Long.toHexString(entry.getCrc()), entry.getSize(), targetWidth);
            if (etag.equals(ifNoneMatch)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
            }
            if (targetWidth != null) {
                Optional<byte[]> scaled = scaleToJpeg(zipFile, entry, targetWidth);
                if (scaled.isPresent()) {
                    byte[] jpeg = scaled.get();
                    return ResponseEntity.ok()
                            .eTag(etag)
                            .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_IMMUTABLE)
                            .contentType(MediaType.IMAGE_JPEG)
                            .contentLength(jpeg.length)
                            .body(output -> output.write(jpeg));
                }
            }
            byte[] bytes;
            try (InputStream in = zipFile.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            return ResponseEntity.ok()
                    .eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_IMMUTABLE)
                    .contentType(PAGE_CONTENT_TYPES.getOrDefault(extensionOf(entryName), MediaType.APPLICATION_OCTET_STREAM))
                    .contentLength(bytes.length)
                    .body(output -> output.write(bytes));
        }
    }

    /**
     * One rasterized pdf page. The page count comes from the scan (the entity), so a request
     * never opens the document just to bounds-check; the ETag derives from the source file's
     * identity (size + mtime) since there is no zip CRC to key on.
     */
    private ResponseEntity<StreamingResponseBody> pdfPage(
            MediaFileEntity entity, Path path, int index, Integer width, String ifNoneMatch) throws IOException {
        Integer pageCount = entity.getPageCount();
        if (pageCount == null || index < 0 || index >= pageCount) {
            return ResponseEntity.notFound().build();
        }
        Integer bucket = bucketWidth(width);
        int renderWidth = bucket != null ? bucket : DEFAULT_PDF_WIDTH;
        String etag = "\"pdf-%d-%d-p%d-w%d\"".formatted(
                Files.size(path), Files.getLastModifiedTime(path).toMillis(), index, renderWidth);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        Optional<Path> rendered = pdfPageCache.pageJpeg(entity.getId(), path, index, renderWidth);
        if (rendered.isEmpty()) {
            return ResponseEntity.internalServerError().build();
        }
        Path cacheFile = rendered.get();
        return ResponseEntity.ok()
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_IMMUTABLE)
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(Files.size(cacheFile))
                .body(output -> Files.copy(cacheFile, output));
    }

    /** Allowed downscale widths; a requested width snaps to the smallest bucket that covers it. */
    private static final int[] WIDTH_BUCKETS = {240, 480};

    private static Integer bucketWidth(Integer width) {
        if (width == null || width <= 0) {
            return null;
        }
        for (int bucket : WIDTH_BUCKETS) {
            if (width <= bucket) {
                return bucket;
            }
        }
        return WIDTH_BUCKETS[WIDTH_BUCKETS.length - 1];
    }

    /**
     * The entry downscaled to {@code targetWidth} as jpeg bytes, or empty when the source is
     * already narrower or scaling is unavailable (undecodable image, native image without AWT) —
     * the caller then streams the original.
     */
    @SuppressWarnings("java:S1181") // a native image without AWT throws LinkageError, which must degrade, not propagate
    private Optional<byte[]> scaleToJpeg(ZipFile zipFile, ZipEntry entry, int targetWidth) {
        try (InputStream in = zipFile.getInputStream(entry)) {
            BufferedImage source = ImageIO.read(in);
            if (source == null || source.getWidth() <= targetWidth) {
                return Optional.empty();
            }
            int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, targetWidth, targetHeight, java.awt.Color.WHITE, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(scaled, "jpg", out)) {
                return Optional.empty();
            }
            return Optional.of(out.toByteArray());
        } catch (Throwable t) {
            // Throwable on purpose: a native image without AWT throws LinkageError/
            // ExceptionInInitializerError, and a broken page must degrade to the original bytes.
            log.warn("Could not downscale comic page {}: {}", entry.getName(), t.toString());
            return Optional.empty();
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
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_IMMUTABLE)
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
