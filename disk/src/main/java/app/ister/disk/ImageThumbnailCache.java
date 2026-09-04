package app.ister.disk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk cache for downscaled artwork, following {@link PdfPageCache}: exists-or-generate with a
 * per-file lock against duplicate work and a last-modified touch on every hit, so the idle sweep
 * ({@code ImageThumbnailCleanupScheduler}) can tell hot artwork from cold.
 * <p>
 * Two placement decisions worth keeping:
 * <ul>
 *   <li>Under {@code tmp-dir}, not {@code cache-dir}: the cache-dir cleanup deletes files no
 *       database row references, which a derived thumbnail never has.</li>
 *   <li>Under a fixed {@code image-thumbs/} directory rather than one named by image id. The tmp
 *       sweep deletes top-level directories whose UUID name is not a media file and leaves
 *       non-UUID names alone, so keying by image id at the top level would have every thumbnail
 *       swept away as an orphan.</li>
 * </ul>
 * The source file's mtime and size are part of the file name, so artwork replaced in place (a
 * rescan keeps the image id) lands on a fresh path instead of serving a stale derivative — the
 * same identity the ETag is built from. Superseded files simply go idle and get swept.
 */
@Slf4j
@Component
public class ImageThumbnailCache {

    /** Top-level directory under tmp-dir. Deliberately not a UUID — see the class javadoc. */
    public static final String THUMBNAIL_DIR = "image-thumbs";

    private final ImageScaler imageScaler;
    private final String tmpDir;

    private final ConcurrentHashMap<Path, Object> locks = new ConcurrentHashMap<>();

    public ImageThumbnailCache(ImageScaler imageScaler, @Value("${app.ister.server.tmp-dir}") String tmpDir) {
        this.imageScaler = imageScaler;
        this.tmpDir = tmpDir;
    }

    /** A cached thumbnail: the file to stream and the type it was encoded as. */
    public record Thumbnail(Path path, MediaType contentType) {
    }

    /**
     * The cached thumbnail of {@code source} at {@code width} pixels wide, generating it on a
     * miss. Empty when the source is already that narrow or cannot be scaled — the caller then
     * serves the original file, which is always available here.
     */
    public Optional<Thumbnail> thumbnail(UUID imageId, Path source, int width) throws IOException {
        String signature = "%s-%d".formatted(
                Long.toHexString(Files.getLastModifiedTime(source).toMillis()), Files.size(source));
        Path jpeg = cacheFile(imageId, signature, width, "jpg");
        Path png = cacheFile(imageId, signature, width, "png");
        Optional<Thumbnail> hit = hit(jpeg, MediaType.IMAGE_JPEG).or(() -> hit(png, MediaType.IMAGE_PNG));
        if (hit.isPresent()) {
            return hit;
        }
        // Lock on the jpeg path for both formats: which one a source produces is decided by the
        // scaler, so the pair is one cache entry with one generation.
        synchronized (locks.computeIfAbsent(jpeg, k -> new Object())) {
            hit = hit(jpeg, MediaType.IMAGE_JPEG).or(() -> hit(png, MediaType.IMAGE_PNG));
            if (hit.isPresent()) {
                return hit;
            }
            Optional<ImageScaler.ScaledImage> scaled;
            try (InputStream in = Files.newInputStream(source)) {
                scaled = imageScaler.scale(in, width, ImageScaler.Alpha.PRESERVE, "image " + imageId);
            }
            if (scaled.isEmpty()) {
                return Optional.empty();
            }
            MediaType contentType = scaled.get().contentType();
            Path target = MediaType.IMAGE_PNG.equals(contentType) ? png : jpeg;
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.write(part, scaled.get().bytes());
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(new Thumbnail(target, contentType));
        }
    }

    private Optional<Thumbnail> hit(Path cacheFile, MediaType contentType) {
        if (!Files.exists(cacheFile)) {
            return Optional.empty();
        }
        try {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            // Only costs the file its place in the idle sweep; serving it still works.
            log.debug("Could not touch thumbnail {}: {}", cacheFile, e.getMessage());
        }
        return Optional.of(new Thumbnail(cacheFile, contentType));
    }

    /**
     * {@code image-thumbs/{first two hex chars}/{id}-{mtime}-{size}-w{width}.{ext}}. The shard
     * keeps a large library from putting every thumbnail in one directory.
     */
    private Path cacheFile(UUID imageId, String signature, int width, String extension) {
        String id = imageId.toString();
        return Path.of(tmpDir, THUMBNAIL_DIR, id.substring(0, 2),
                "%s-%s-w%d.%s".formatted(id, signature, width, extension));
    }
}
