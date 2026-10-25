package app.ister.worker.events.gamefound;

import app.ister.core.entity.GameEntity;

/**
 * A source of game metadata (description, release year, cover) by title and platform. Today that
 * is Steam's artwork ({@link SteamGameMetadataProvider}); IGDB can slot in behind the same call.
 */
public interface GameMetadataProvider {

    /**
     * Enriches the game in place (title, release year, metadata rows, artwork). Must be
     * idempotent: it runs again on every GAME_FOUND of the same game.
     *
     * @return whether anything on the entity changed
     */
    boolean enrich(GameEntity game);
}
