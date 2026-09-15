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
     * A processed chunk. {@code lastId} is the id of the final image, in the database's ordering,
     * and becomes the next cursor -- also when the image itself could not be hashed, which is what
     * guarantees the sweep terminates.
     */
    public record Chunk(int size, UUID lastId) {
        public boolean isEmpty() {
            return size == 0;
        }
    }

    @Transactional
    public Chunk process(UUID directoryEntityId, UUID afterId, int chunkSize) {
        List<ImageEntity> images = fetch(directoryEntityId, afterId, Limit.of(chunkSize));
        if (images.isEmpty()) {
            return new Chunk(0, null);
        }
        DirectoryEntity directory = directoryRepository.findById(directoryEntityId).orElseThrow();
        images.forEach(image -> applyBlurHash(directory, image));
        imageRepository.saveAll(images);
        return new Chunk(images.size(), images.getLast().getId());
    }

    private List<ImageEntity> fetch(UUID directoryEntityId, UUID afterId, Limit limit) {
        return Optional.ofNullable(afterId)
                .map(cursor -> imageRepository.findByDirectoryEntityIdAndBlurHashIsNullAndIdGreaterThanOrderById(
                        directoryEntityId, cursor, limit))
                .orElseGet(() -> imageRepository.findByDirectoryEntityIdAndBlurHashIsNullOrderById(
                        directoryEntityId, limit));
    }

    private void applyBlurHash(DirectoryEntity directory, ImageEntity imageEntity) {
        try (LocalCopy.Handle local = localCopy.of(directory, imageEntity.getPath())) {
            BufferedImage bi = RasterImageDecoder.read(local.path().toFile());
            String blurHash = BlurHash.encode(bi);

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
