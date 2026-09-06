package app.ister.disk;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.DirectoryType;
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
    public ResponseEntity<Resource> downloadMediaFile(@PathVariable UUID id) {
        var mediaFileEntity = mediaFileRepository.findById(id).orElseThrow();
        return fileResource(Path.of(mediaFileEntity.getPath()));
    }

    /**
     * An external subtitle file (extracted or sidecar {@code .srt}) for another node. Its path is
     * local to this node — the cache directory, or next to the media — so a helper transcoding
     * this file can only get at it here.
     */
    @GetMapping("/mediaFileStream/{id}/download")
    public ResponseEntity<Resource> downloadMediaFileStream(@PathVariable UUID id) {
        return mediaFileStreamRepository.findById(id)
                .filter(stream -> stream.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE)
                .map(stream -> fileResource(Path.of(stream.getPath())))
                .orElseGet(() -> ResponseEntity.notFound().build());
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
        DirectoryEntity cacheDir = directoryRepository
                .findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeService.getOrCreateNodeEntityForThisNode())
                .stream().findFirst().orElseThrow();
        Path target = Path.of(cacheDir.getPath(), safeName);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + safeName + ".upload-" + UUID.randomUUID());
        try (InputStream in = request.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
