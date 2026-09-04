package app.ister.disk;

import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.utils.SafeFilename;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
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

    @GetMapping("/mediaFile/{id}/download")
    public InputStreamResource downloadMediaFile(@PathVariable UUID id) throws IOException {
        var mediaFileEntity = mediaFileRepository.findById(id).orElseThrow();
        return new InputStreamResource(new FileInputStream(mediaFileEntity.getPath())) {
            @Override
            public long contentLength() throws IOException {
                return Files.size(Path.of(mediaFileEntity.getPath()));
            }
        };
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
