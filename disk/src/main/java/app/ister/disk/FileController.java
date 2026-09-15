package app.ister.disk;

import app.ister.core.enums.StreamCodecType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.service.NodeService;
import app.ister.core.utils.SafeFilename;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import app.ister.core.entity.ImageEntity;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.ObjectRef;
import app.ister.core.storage.ObjectStat;
import app.ister.core.storage.ObjectStore;
import app.ister.core.storage.ObjectStoreRegistry;
import app.ister.core.storage.RangedObject;
import app.ister.disk.http.ByteRanges;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
//@SecurityRequirement(name = "oidc_auth")
public class FileController {
    /**
     * Images are user-scoped (stream token / bearer, and MediaAccessEnforcementFilter gates them
     * by library), so never {@code public}. Not {@code immutable} either, unlike the comic and
     * epub resources: a scanned library image keeps its id when the file behind it is replaced in
     * place, so clients have to be able to revalidate — which the ETag below makes cheap.
     */
    private static final String CACHE_CONTROL_REVALIDATE = "private, max-age=86400";

    /**
     * Allowed downscale widths; a requested width snaps up to the smallest bucket that covers it.
     * Keep in sync with {@code ArtworkSizing.widthBuckets} in the player, which sends widths off
     * the same ladder — the bucketing here is what stops a hand-written url from filling the disk
     * with one-off sizes.
     */
    private static final int[] WIDTH_BUCKETS = {160, 240, 320, 480, 640, 960, 1280};

    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;
    private final ImageThumbnailCache imageThumbnailCache;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final NodeService nodeService;
    private final DirectoryRepository directoryRepository;
    private final ObjectStoreRegistry objectStoreRegistry;
    private final CacheDirectoryResolver cacheDirectoryResolver;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    /**
     * The artwork file, optionally downscaled to {@code ?width=}.
     * <p>
     * The width snaps to {@link #WIDTH_BUCKETS}; above the top bucket, and whenever scaling is not
     * possible (source already narrower, undecodable, no writer, no AWT), the original file is
     * served. So a client that sends no width, or an unknown width, gets exactly what it always
     * got — which is also what makes the parameter safe to add without a capability handshake.
     */
    @GetMapping("/images/{id}/download")
    public ResponseEntity<InputStreamResource> downloadImage(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer width,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch)
            throws IOException {
        var imageEntity = imageRepository.findById(id).orElseThrow();
        if (ObjectRef.isS3Uri(imageEntity.getPath())) {
            return downloadS3Image(imageEntity, width, ifNoneMatch);
        }
        Path imagePath = Path.of(imageEntity.getPath());
        if (!Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }
        Integer bucket = bucketWidth(width);
        // Answer the conditional request before opening the stream: a 304 that returns past an
        // open FileInputStream leaks a descriptor on every cache hit. Doing it before the
        // thumbnail lookup also means a revalidation never triggers a scale.
        String identity = "%s-%d".formatted(
                Long.toHexString(Files.getLastModifiedTime(imagePath).toMillis()),
                Files.size(imagePath));
        String etag = bucket == null ? "\"%s\"".formatted(identity) : "\"%s-w%d\"".formatted(identity, bucket);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_REVALIDATE)
                    .build();
        }
        Path body = imagePath;
        MediaType contentType = null;
        if (bucket != null) {
            Optional<ImageThumbnailCache.Thumbnail> thumbnail =
                    imageThumbnailCache.thumbnail(id, imagePath, bucket);
            if (thumbnail.isPresent()) {
                body = thumbnail.get().path();
                contentType = thumbnail.get().contentType();
            }
        }
        if (contentType == null) {
            String probed = Files.probeContentType(imagePath);
            contentType = MediaType.parseMediaType(
                    probed != null ? probed : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        }
        Path served = body;
        InputStreamResource resource = new InputStreamResource(new FileInputStream(served.toFile())) {
            @Override
            public long contentLength() throws IOException {
                return Files.size(served);
            }
        };
        return ResponseEntity.ok()
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_REVALIDATE)
                .contentType(contentType)
                .body(resource);
    }

    /**
     * The bucket to render at, or null to serve the original: no width asked, or a width above the
     * top bucket, where re-encoding costs a decode and saves little.
     */
    private static Integer bucketWidth(Integer width) {
        if (width == null || width <= 0 || width > WIDTH_BUCKETS[WIDTH_BUCKETS.length - 1]) {
            return null;
        }
        for (int bucket : WIDTH_BUCKETS) {
            if (width <= bucket) {
                return bucket;
            }
        }
        return null;
    }

    /**
     * The raw media file for another node (download token). Returned as a {@link FileSystemResource},
     * never an {@code InputStreamResource}: Spring only serves byte ranges for real file
     * resources, and ffmpeg on the helper node relies on {@code Accept-Ranges: bytes} to seek —
     * a helper fingerprinting the outro window, or probing the end of a stream, would otherwise
     * pull the whole file over the network for every seek.
     */
    @GetMapping("/mediaFile/{id}/download")
    public ResponseEntity<Resource> downloadMediaFile(@PathVariable UUID id,
                                               @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                                               HttpServletRequest request) throws IOException {
        var mediaFileEntity = mediaFileRepository.findById(id).orElseThrow();
        if (ObjectRef.isS3Uri(mediaFileEntity.getPath())) {
            return objectResource(objectStoreRegistry.forEntity(mediaFileEntity), ObjectStoreRegistry.keyOf(mediaFileEntity),
                    rangeHeader, request);
        }
        return fileResource(Path.of(mediaFileEntity.getPath()));
    }

    /**
     * An external subtitle file (extracted or sidecar {@code .srt}) for another node. Its path is
     * local to this node — the cache directory, or next to the media — so a helper transcoding
     * this file can only get at it here.
     */
    @GetMapping("/mediaFileStream/{id}/download")
    public ResponseEntity<Resource> downloadMediaFileStream(@PathVariable UUID id,
                                                     @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                                                     HttpServletRequest request) throws IOException {
        var stream = mediaFileStreamRepository.findById(id)
                .filter(s -> s.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE)
                .orElse(null);
        if (stream == null) {
            return ResponseEntity.notFound().build();
        }
        if (ObjectRef.isS3Uri(stream.getPath())) {
            // a sidecar .srt in an S3 library directory, or an extracted one in the shared S3 cache
            ObjectStore store = objectStoreRegistry.forUri(stream.getPath()).orElse(null);
            if (store == null) {
                return ResponseEntity.notFound().build();
            }
            return objectResource(store, ObjectRef.parse(stream.getPath()).key(), rangeHeader, request);
        }
        return fileResource(Path.of(stream.getPath()));
    }

    /**
     * An S3 object proxied with byte-range support: the same contract as {@link #fileResource}
     * ({@code Accept-Ranges: bytes}, 206 + {@code Content-Range} for a single range, 416 when the
     * range starts past the end), so ffmpeg on a helper — or on this very node — can seek in an
     * S3 file without S3 being reachable from where ffmpeg runs. The object is opened with the
     * requested range, never skipped through: the body is an {@link InputStreamResource} (which
     * Spring deliberately leaves out of its own, skip-based range handling) and the 206 status
     * is set here. A HEAD gets the headers without a GET to S3.
     */
    @SuppressWarnings("java:S2095") // the opened body is owned by the response; the container closes it after writing
    ResponseEntity<Resource> objectResource(ObjectStore store, String key, String rangeHeader, HttpServletRequest request)
            throws IOException {
        Optional<ObjectStat> stat = store.stat(key);
        if (stat.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        long size = stat.get().size();
        ByteRanges.Range range = null;
        if (rangeHeader != null) {
            range = ByteRanges.parseRange(rangeHeader, size);
            if (range == null) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                        .build();
            }
        }
        long from = range == null ? 0 : range.start();
        long to = range == null ? size - 1 : range.end();
        long length = to - from + 1;
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(range != null ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(length);
        if (stat.get().etag() != null) {
            response.eTag(stat.get().etag().startsWith("\"") ? stat.get().etag() : "\"" + stat.get().etag() + "\"");
        }
        if (range != null) {
            response.header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(from, to, size));
        }
        if (request != null && "HEAD".equalsIgnoreCase(request.getMethod())) {
            return response.build();
        }
        RangedObject object = store.openRange(key, from, to);
        InputStreamResource body = new InputStreamResource(object.body(), store.uri(key)) {
            @Override
            public long contentLength() {
                return length;
            }
        };
        return response.body(body);
    }

    /**
     * Artwork that lives in an S3 directory (a cover next to the media, or — with a shared S3
     * cache — a downloaded poster). Same ETag/width contract as the local branch; thumbnails are
     * still generated into the local tmp dir, keyed on the object's ETag.
     */
    private ResponseEntity<InputStreamResource> downloadS3Image(ImageEntity imageEntity, Integer width, String ifNoneMatch)
            throws IOException {
        ObjectStore store = objectStoreRegistry.forEntity(imageEntity);
        String key = ObjectStoreRegistry.keyOf(imageEntity);
        Optional<ObjectStat> stat = store.stat(key);
        if (stat.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Integer bucket = bucketWidth(width);
        String identity = stat.get().etag() != null
                ? stat.get().etag().replace("\"", "")
                : "%s-%d".formatted(Long.toHexString(stat.get().lastModified().toEpochMilli()), stat.get().size());
        String etag = bucket == null ? "\"%s\"".formatted(identity) : "\"%s-w%d\"".formatted(identity, bucket);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_REVALIDATE)
                    .build();
        }
        if (bucket != null) {
            Optional<ImageThumbnailCache.Thumbnail> thumbnail =
                    imageThumbnailCache.thumbnail(imageEntity.getId(), identity, () -> store.open(key), bucket);
            if (thumbnail.isPresent()) {
                Path served = thumbnail.get().path();
                InputStreamResource resource = new InputStreamResource(new FileInputStream(served.toFile())) {
                    @Override
                    public long contentLength() throws IOException {
                        return Files.size(served);
                    }
                };
                return ResponseEntity.ok()
                        .eTag(etag)
                        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_REVALIDATE)
                        .contentType(thumbnail.get().contentType())
                        .body(resource);
            }
        }
        String contentType = stat.get().contentType();
        if (contentType == null || MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(contentType)) {
            contentType = key.toLowerCase().endsWith(".png") ? MediaType.IMAGE_PNG_VALUE : MediaType.IMAGE_JPEG_VALUE;
        }
        long size = stat.get().size();
        InputStreamResource resource = new InputStreamResource(store.open(key)) {
            @Override
            public long contentLength() {
                return size;
            }
        };
        return ResponseEntity.ok()
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_REVALIDATE)
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private static ResponseEntity<Resource> fileResource(Path path) {
        if (!Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    /**
     * A helper node pushes an artefact it produced for one of this node's files (an extracted
     * subtitle) into this node's cache directory, where the database row points at it. Written
     * to a temp name and moved atomically, so a reader never sees a half-written file.
     */
    @PostMapping("/cache/upload/{fileName}")
    public ResponseEntity<Void> uploadCacheFile(@PathVariable String fileName, HttpServletRequest request) throws IOException {
        String safeName = SafeFilename.require(fileName);
        // Spooled to tmp first: the request body has no known length, and the store wants a file
        // it can move into place (local) or upload with a content length (S3).
        Path tmp = Path.of(tmpDir, "upload-" + UUID.randomUUID() + "-" + safeName);
        Files.createDirectories(tmp.getParent());
        try (InputStream in = request.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            cacheDirectoryResolver.store().write(safeName, tmp, null);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/transcode/upload/{id}/{fileName}")
    public ResponseEntity<Void> uploadTranscode(
            @PathVariable UUID id,
            @PathVariable String fileName,
            HttpServletRequest request) throws IOException {
        Path dir = Path.of(tmpDir, id.toString());
        Files.createDirectories(dir);
        try (InputStream in = request.getInputStream()) {
            Files.copy(in, dir.resolve(SafeFilename.require(fileName)), StandardCopyOption.REPLACE_EXISTING);
        }
        return ResponseEntity.ok().build();
    }
}
