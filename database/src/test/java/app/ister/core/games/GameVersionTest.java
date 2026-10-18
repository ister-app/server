package app.ister.core.games;

import app.ister.core.entity.GameEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameVersionTest {

    @Test
    void steamBuildIdWinsWhenSet() {
        GameEntity game = GameEntity.builder().steamBuildId(1234567L).sizeBytes(10).fileCount(2).build();

        assertEquals("1234567", GameVersion.of(game));
    }

    @Test
    void digestIsStableAndSixteenHexChars() {
        Instant updated = Instant.parse("2026-10-18T10:00:00Z");
        GameEntity a = GameEntity.builder().sizeBytes(10).fileCount(2).dateUpdated(updated).build();
        GameEntity b = GameEntity.builder().sizeBytes(10).fileCount(2).dateUpdated(updated).build();

        assertEquals(GameVersion.of(a), GameVersion.of(b));
        assertTrue(GameVersion.of(a).matches("[0-9a-f]{16}"), GameVersion.of(a));
    }

    @Test
    void digestMovesWithSizeCountOrUpdateTime() {
        Instant updated = Instant.parse("2026-10-18T10:00:00Z");
        GameEntity base = GameEntity.builder().sizeBytes(10).fileCount(2).dateUpdated(updated).build();

        assertNotEquals(GameVersion.of(base),
                GameVersion.of(GameEntity.builder().sizeBytes(11).fileCount(2).dateUpdated(updated).build()));
        assertNotEquals(GameVersion.of(base),
                GameVersion.of(GameEntity.builder().sizeBytes(10).fileCount(3).dateUpdated(updated).build()));
        assertNotEquals(GameVersion.of(base),
                GameVersion.of(GameEntity.builder().sizeBytes(10).fileCount(2).dateUpdated(updated.plusSeconds(1)).build()));
    }

    @Test
    void anUnsavedRowStillHasAVersion() {
        assertTrue(GameVersion.of(GameEntity.builder().build()).matches("[0-9a-f]{16}"));
    }
}
