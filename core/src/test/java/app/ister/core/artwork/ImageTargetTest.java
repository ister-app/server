package app.ister.core.artwork;

import app.ister.core.entity.ImageEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageTargetTest {

    @Test
    void ofRequiresExactlyOneId() {
        UUID id = UUID.randomUUID();
        assertEquals(new ImageTarget(ImageTarget.Kind.ALBUM, id),
                ImageTarget.of(null, null, null, id, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ImageTarget.of(null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ImageTarget.of(id, null, id, null, null, null, null, null, null));
    }

    @Test
    void applyToPointsTheRowAtOneItemOnly() {
        UUID movie = UUID.randomUUID();
        UUID episode = UUID.randomUUID();
        ImageEntity image = ImageEntity.builder().movieEntityId(movie).build();

        new ImageTarget(ImageTarget.Kind.EPISODE, episode).applyTo(image);

        assertNull(image.getMovieEntityId());
        assertEquals(episode, image.getEpisodeEntityId());
        assertEquals(new ImageTarget(ImageTarget.Kind.EPISODE, episode), ImageTarget.ofImage(image));
        assertTrue(ImageTarget.ofImage(image).hasVideo());
        assertNull(ImageTarget.ofImage(ImageEntity.builder().build()));
    }
}
