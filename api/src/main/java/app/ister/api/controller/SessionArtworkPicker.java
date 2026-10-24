package app.ister.api.controller;

import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MetadataSource;

import java.util.List;
import java.util.UUID;

/**
 * Picks the cover for a now-playing card out of an item's images: COVER images when there are
 * any, an admin's choice ({@link ImageEntity#isSelected()}) first, then local artwork (shipped
 * next to the media files) over scraped provider images, then anything — the same preference the
 * clients apply when they resolve artwork themselves (ImageUtil.getImageByType), so a session
 * card and the local mini player show the same cover.
 */
final class SessionArtworkPicker {

    private SessionArtworkPicker() {
    }

    static UUID pick(List<ImageEntity> images) {
        List<ImageEntity> covers =
                images.stream().filter(image -> image.getType() == ImageType.COVER).toList();
        List<ImageEntity> pool = covers.isEmpty() ? images : covers;
        return pool.stream()
                .filter(ImageEntity::isSelected)
                .findFirst()
                .or(() -> pool.stream()
                        .filter(image -> image.getSource() == null || image.getSource() == MetadataSource.LOCAL_FILE)
                        .findFirst())
                .or(() -> pool.stream().findFirst())
                .map(ImageEntity::getId)
                .orElse(null);
    }
}
