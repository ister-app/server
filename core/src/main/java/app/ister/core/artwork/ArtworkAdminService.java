package app.ister.core.artwork;

import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.ImageType;

import java.io.IOException;
import java.util.UUID;

/**
 * The admin's say over an item's artwork: which of the stored images is the one to show
 * ({@link ImageEntity#isSelected()} — at most one per item and type), and new images that are not
 * a scan's doing: a provider alternative, a frame from the item's own video, an upload. A selected
 * row survives a forced metadata refresh, which otherwise wipes and refetches every image.
 *
 * <p>Implemented in the disk module (it writes the cache store and runs ffmpeg); the api module
 * calls it through this interface.
 */
public interface ArtworkAdminService {

    /** Marks a stored image as the one to show for its item and type; clears the previous choice. */
    ImageEntity select(UUID imageId);

    /** Downloads a provider candidate (by the {@code sourceUri} a provider handed out), stores and selects it. */
    ImageEntity selectRemote(ImageTarget target, ImageType type, String sourceUri) throws IOException;

    /**
     * Grabs one frame of the media file at {@code positionMs} (the file's own timeline — for a file
     * holding several episodes the caller adds the episode's start), stores it and selects it.
     */
    ImageEntity selectVideoFrame(ImageTarget target, ImageType type, UUID mediaFileId, long positionMs) throws IOException;

    /** Stores an uploaded image (jpeg/png/webp body) and selects it. */
    ImageEntity storeUpload(ImageTarget target, ImageType type, byte[] body, String contentType, String sourceUri) throws IOException;

    /** Drops the choice for an item and type; the automatic rule picks again. */
    boolean clearSelected(ImageTarget target, ImageType type);

    /** Deletes an image the admin added ({@code source == USER}); refuses scanned/provider images. */
    boolean deleteUserImage(UUID imageId);
}
