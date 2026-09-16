package app.ister.disk.events.updateimagesrequested;

import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.storage.FileAccess;
import app.ister.core.storage.LocalCopy;
import app.ister.core.storage.ObjectStat;
import app.ister.disk.RasterImageDecoder;
import io.trbl.blurhash.BlurHash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.image.BufferedImage;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes blur-hashes for one chunk of a directory's images, in its own transaction.
 *
 * <p>Deliberately a separate bean from {@link HandleUpdateImagesRequested}: the handler must publish
 * the successor message only after this transaction has committed. Were the publish to happen inside
 * the transaction, a failing commit would still leave a successor on the queue whose cursor has moved
 * past a chunk that was never saved.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BlurHashChunkProcessor {

    private final ImageRepository imageRepository;
    private final DirectoryRepository directoryRepository;
    private final LocalCopy localCopy;
    private final FileAccess fileAccess;

    /**
     * Wall-clock budget for one chunk. RabbitMQ's consumer timeout (30 minutes by default) closes
     * the channel of a delivery that takes longer, and the chunk is then redelivered and starts
     * over: a slow chunk would loop forever. A chunk that runs out of time is committed as far as
     * it got and the sweep continues from there with the next message.
     */
    @Value("${app.ister.server.blur-hash.chunk-seconds:120}")
    private long chunkSeconds = 120;

    /** Blur-hashes describe a few gradients; encoding a poster at full size is wasted CPU. */
    static final int ENCODE_MAX_SIDE = 96;

    /**
     * A processed chunk. {@code lastId} is the id of the final image, in the database's ordering,
     * and becomes the next cursor -- also when the image itself could not be hashed, which is what
     * guarantees the sweep terminates. {@code exhausted} means the directory has no images left
     * after this chunk; a chunk cut short by the time budget is never exhausted.
     */
    public record Chunk(int size, UUID lastId, boolean exhausted) {
        public boolean isEmpty() {
            return size == 0;
        }
    }

    @Transactional
    public Chunk process(UUID directoryEntityId, UUID afterId, int chunkSize) {
        List<ImageEntity> images = fetch(directoryEntityId, afterId, Limit.of(chunkSize));
        if (images.isEmpty()) {
            return new Chunk(0, null, true);
        }
        DirectoryEntity directory = directoryRepository.findById(directoryEntityId).orElseThrow();
        long deadline = System.currentTimeMillis() + chunkSeconds * 1000;
        int processed = 0;
        for (ImageEntity image : images) {
            applyBlurHash(directory, image);
            processed++;
            if (processed < images.size() && System.currentTimeMillis() >= deadline) {
                log.info("Blur-hash chunk time budget of {}s spent after {} of {} images; continuing in the next chunk",
                        chunkSeconds, processed, images.size());
                break;
            }
        }
        List<ImageEntity> done = images.subList(0, processed);
        imageRepository.saveAll(done);
        boolean exhausted = processed == images.size() && images.size() < chunkSize;
        return new Chunk(processed, done.getLast().getId(), exhausted);
    }

    private List<ImageEntity> fetch(UUID directoryEntityId, UUID afterId, Limit limit) {
        return Optional.ofNullable(afterId)
                .map(cursor -> imageRepository.findByDirectoryEntityIdAndBlurHashIsNullAndIdGreaterThanOrderById(
                        directoryEntityId, cursor, limit))
                .orElseGet(() -> imageRepository.findByDirectoryEntityIdAndBlurHashIsNullOrderById(
                        directoryEntityId, limit));
    }

    /**
     * Nearest-neighbour downscale to at most {@code maxSide} pixels on the long side. Plain pixel
     * sampling on purpose: no Graphics2D, which keeps it safe in the native image.
     */
    static BufferedImage downscale(BufferedImage source, int maxSide) {
        int longSide = Math.max(source.getWidth(), source.getHeight());
        if (longSide <= maxSide) {
            return source;
        }
        int step = (int) Math.ceil((double) longSide / maxSide);
        int width = Math.max(1, source.getWidth() / step);
        int height = Math.max(1, source.getHeight() / step);
        BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                small.setRGB(x, y, source.getRGB(x * step, y * step));
            }
        }
        return small;
    }

    private void applyBlurHash(DirectoryEntity directory, ImageEntity imageEntity) {
        try (LocalCopy.Handle local = localCopy.of(directory, imageEntity.getPath())) {
            BufferedImage bi = RasterImageDecoder.read(local.path().toFile());
            String blurHash = BlurHash.encode(downscale(bi, ENCODE_MAX_SIDE));

            ObjectStat stat = fileAccess.stat(directory, imageEntity.getPath())
                    .orElseThrow(() -> new java.nio.file.NoSuchFileException(imageEntity.getPath()));

            imageEntity.setBlurHash(blurHash);
            imageEntity.setFileLastModifiedTime(stat.lastModified());
            imageEntity.setFileCreationTime(FileAccess.creationTime(imageEntity.getPath(), stat));

            log.debug("Updated blur-hash for {}", imageEntity.getPath());
        } catch (IOException | RuntimeException | LinkageError e) {
            // Best-effort per image: a corrupt file, or a native-image image-decoding issue (e.g.
            // AWT/CMM LinkageError) must not fail the chunk. Colour-space oddities ImageIO refuses
            // outright are handled a level down, by RasterImageDecoder's raster fallback.
            // The image keeps a null blur-hash; the cursor moves past it regardless.
            log.error("Unable to process imageEntity {}: {}", imageEntity.getPath(), e.getMessage());
        }
    }
}
