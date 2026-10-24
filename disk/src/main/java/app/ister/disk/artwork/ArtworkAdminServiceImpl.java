package app.ister.disk.artwork;

import app.ister.core.artwork.ArtworkAdminService;
import app.ister.core.artwork.ImageTarget;
import app.ister.core.artwork.RemoteArtworkProvider;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MetadataSource;
import app.ister.core.http.JdkHttpClients;
import app.ister.core.node.MediaFileInputResolver;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import app.ister.core.utils.BackgroundFfmpeg;
import app.ister.disk.RasterImageDecoder;
import app.ister.disk.events.mediafilefound.MediaFileFoundCreateBackground;
import app.ister.disk.events.updateimagesrequested.BlurHashChunkProcessor;
import com.github.kokorin.jaffree.process.JaffreeAbnormalExitException;
import io.trbl.blurhash.BlurHash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ArtworkAdminService} on this node: new images land in this node's cache store under
 * {@code artwork/}, as a row created right here (not through the {@code IMAGE_FOUND} event — the
 * admin is waiting for the answer, and the row must exist before the mutation returns) with its
 * blur-hash computed inline, since the blur-hash sweep only runs on a metadata refresh.
 *
 * <p>A frame grab reads the media file through {@link MediaFileInputResolver}, so it works on a
 * node that does not hold the file (owner download URL, S3), at the background ffmpeg priority.
 */
@Slf4j
@Service
public class ArtworkAdminServiceImpl implements ArtworkAdminService {

    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";
    private static final HttpClient HTTP_CLIENT = JdkHttpClients.httpClient(Duration.ofSeconds(10));
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(2);

    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;
    private final CacheDirectoryResolver cacheDirectoryResolver;
    private final MediaFileInputResolver mediaFileInputResolver;
    private final MediaFileFoundCreateBackground createBackground;
    private final List<RemoteArtworkProvider> providers;
    private final String dirOfFFmpeg;
    private final String tmpDir;

    public ArtworkAdminServiceImpl(ImageRepository imageRepository,
                                   MediaFileRepository mediaFileRepository,
                                   CacheDirectoryResolver cacheDirectoryResolver,
                                   MediaFileInputResolver mediaFileInputResolver,
                                   MediaFileFoundCreateBackground createBackground,
                                   List<RemoteArtworkProvider> providers,
                                   BackgroundFfmpeg backgroundFfmpeg,
                                   @Value("${app.ister.server.tmp-dir}") String tmpDir) {
        this.imageRepository = imageRepository;
        this.mediaFileRepository = mediaFileRepository;
        this.cacheDirectoryResolver = cacheDirectoryResolver;
        this.mediaFileInputResolver = mediaFileInputResolver;
        this.createBackground = createBackground;
        this.providers = providers;
        this.dirOfFFmpeg = backgroundFfmpeg.dir();
        this.tmpDir = tmpDir;
    }

    @Override
    @Transactional
    public ImageEntity select(UUID imageId) {
        ImageEntity image = imageRepository.findById(imageId)
                .orElseThrow(() -> new NoSuchElementException("Image not found: " + imageId));
        ImageTarget target = ImageTarget.ofImage(image);
        if (target == null) {
            throw new IllegalArgumentException("Image " + imageId + " belongs to no selectable item");
        }
        return markSelected(target, image);
    }

    @Override
    @Transactional
    public ImageEntity selectRemote(ImageTarget target, ImageType type, String sourceUri) throws IOException {
        String url = providers.stream()
                .map(p -> p.downloadUrl(sourceUri))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No provider offers " + sourceUri));
        // Already stored once (the scan fetched it, or the admin picked it before): reuse the row.
        Optional<ImageEntity> existing = target.imagesOf(imageRepository).stream()
                .filter(i -> i.getType() == type && sourceUri.equals(i.getSourceUri()))
                .findFirst();
        if (existing.isPresent()) {
            return markSelected(target, existing.get());
        }
        Path downloaded = tempFile("artwork-", ".jpg");
        try {
            download(url, downloaded);
            return store(target, type, downloaded, "image/jpeg", "jpg", sourceUri, MetadataSource.fromSourceUri(sourceUri).orElse(null));
        } finally {
            Files.deleteIfExists(downloaded);
        }
    }

    @Override
    @Transactional
    public ImageEntity selectVideoFrame(ImageTarget target, ImageType type, UUID mediaFileId, long positionMs) throws IOException {
        if (!target.hasVideo()) {
            throw new IllegalArgumentException("Only movies and episodes have a video to take a frame from");
        }
        if (positionMs < 0) {
            throw new IllegalArgumentException("positionMs must not be negative");
        }
        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId)
                .orElseThrow(() -> new NoSuchElementException("Media file not found: " + mediaFileId));
        if (mediaFile.getDurationInMilliseconds() > 0 && positionMs >= mediaFile.getDurationInMilliseconds()) {
            throw new IllegalArgumentException("positionMs lies past the end of the file");
        }
        String input = mediaFileInputResolver.resolve(mediaFile);
        Path still = tempFile("still-", ".jpg");
        try {
            try {
                createBackground.createBackground(still, input, dirOfFFmpeg, positionMs);
            } catch (JaffreeAbnormalExitException e) {
                throw new IOException("ffmpeg could not grab a frame at " + positionMs + "ms: " + e.getMessage(), e);
            }
            if (!Files.exists(still) || Files.size(still) == 0) {
                throw new IOException("ffmpeg produced no frame at " + positionMs + "ms");
            }
            String sourceUri = "frame://" + mediaFileId + "@" + positionMs;
            return store(target, type, still, "image/jpeg", "jpg", sourceUri, MetadataSource.USER);
        } finally {
            Files.deleteIfExists(still);
        }
    }

    @Override
    @Transactional
    public ImageEntity storeUpload(ImageTarget target, ImageType type, byte[] body, String contentType, String sourceUri) throws IOException {
        String extension = switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/jpeg" -> "jpg";
            default -> throw new IllegalArgumentException("Unsupported image type " + contentType);
        };
        Path uploaded = tempFile("upload-", "." + extension);
        try {
            Files.write(uploaded, body);
            return store(target, type, uploaded, contentType, extension, sourceUri, MetadataSource.USER);
        } finally {
            Files.deleteIfExists(uploaded);
        }
    }

    @Override
    @Transactional
    public boolean clearSelected(ImageTarget target, ImageType type) {
        List<ImageEntity> selected = target.imagesOf(imageRepository).stream()
                .filter(i -> i.getType() == type && i.isSelected())
                .toList();
        selected.forEach(i -> i.setSelected(false));
        imageRepository.saveAll(selected);
        return !selected.isEmpty();
    }

    @Override
    @Transactional
    public boolean deleteUserImage(UUID imageId) {
        ImageEntity image = imageRepository.findById(imageId)
                .orElseThrow(() -> new NoSuchElementException("Image not found: " + imageId));
        if (image.getSource() != MetadataSource.USER) {
            throw new IllegalArgumentException("Only uploaded or frame images can be deleted; this one came from "
                    + (image.getSource() == null ? "a scan" : image.getSource().getDisplayName()));
        }
        // Row only: the file may sit in another node's cache; the cache cleanup sweeps it.
        imageRepository.delete(image);
        return true;
    }

    /** Writes a finished local file into the cache (consuming it), creates the row and selects it. */
    private ImageEntity store(ImageTarget target, ImageType type, Path file, String contentType, String extension,
                              String sourceUri, MetadataSource source) throws IOException {
        String blurHash = blurHashOf(file);
        CacheStore store = cacheDirectoryResolver.store();
        String storedPath = store.write("artwork/" + UUID.randomUUID() + "." + extension, file, contentType);
        Instant now = Instant.now();
        ImageEntity image = ImageEntity.builder()
                .path(storedPath)
                .type(type)
                .sourceUri(sourceUri)
                .source(source)
                .blurHash(blurHash)
                .fileCreationTime(now)
                .fileLastModifiedTime(now)
                .build();
        image.setDirectoryEntity(store.directory());
        target.applyTo(image);
        return markSelected(target, image);
    }

    private ImageEntity markSelected(ImageTarget target, ImageEntity image) {
        List<ImageEntity> others = target.imagesOf(imageRepository).stream()
                .filter(i -> i.getType() == image.getType() && i.isSelected() && !i.getId().equals(image.getId()))
                .toList();
        others.forEach(i -> i.setSelected(false));
        imageRepository.saveAll(others);
        image.setSelected(true);
        return imageRepository.save(image);
    }

    private static String blurHashOf(Path file) {
        try {
            return BlurHash.encode(BlurHashChunkProcessor.downscale(
                    RasterImageDecoder.read(file.toFile()), BlurHashChunkProcessor.ENCODE_MAX_SIDE));
        } catch (IOException | RuntimeException | LinkageError e) {
            // Best effort, like the sweep: a webp or an odd colour space simply has no blur-hash.
            log.debug("No blur-hash for {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void download(String url, Path to) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();
        HttpResponse<Path> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(to));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
        if (response.statusCode() >= 400) {
            throw new IOException("Downloading " + url + " failed with HTTP " + response.statusCode());
        }
    }

    private Path tempFile(String prefix, String suffix) throws IOException {
        Files.createDirectories(Path.of(tmpDir));
        return Files.createTempFile(Path.of(tmpDir), prefix, suffix);
    }
}
