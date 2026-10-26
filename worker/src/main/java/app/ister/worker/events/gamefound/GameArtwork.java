package app.ister.worker.events.gamefound;

import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.ImageType;

import java.util.List;

/**
 * Whether a game already has artwork of its own. Achievement icons hang off the game as ICON
 * images too, but they are not a cover: a game whose first play session uploaded its
 * achievements must still get one.
 */
final class GameArtwork {

    private GameArtwork() {
    }

    static boolean present(List<ImageEntity> images) {
        return images.stream().anyMatch(image -> image.getType() != ImageType.ICON);
    }
}
