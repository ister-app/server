package app.ister.worker.events.gamefound;

import app.ister.core.entity.GameEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.ImageRepository;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Artwork for Steam games from Steam's public CDN, keyed by app id: the 600x900 library capsule as
 * the cover and the library hero as the background. Only a game without any artwork gets them, so
 * a cover in the game folder (or an admin's choice) always wins and a rescan does not fetch them
 * again. Other games, and Steam games without an app id, are left alone.
 */
@Slf4j
@Component
public class SteamGameMetadataProvider implements GameMetadataProvider {

    private final ImageRepository imageRepository;
    private final ImageDownloadService imageDownloadService;
    private final String cdnBase;
    private final boolean enabled;

    public SteamGameMetadataProvider(
            ImageRepository imageRepository,
            ImageDownloadService imageDownloadService,
            @Value("${app.ister.worker.steam-artwork.cdn-base:https://cdn.cloudflare.steamstatic.com/steam/apps}") String cdnBase,
            @Value("${app.ister.worker.steam-artwork.enabled:true}") boolean enabled) {
        this.imageRepository = imageRepository;
        this.imageDownloadService = imageDownloadService;
        this.cdnBase = cdnBase;
        this.enabled = enabled;
    }

    @Override
    public boolean enrich(GameEntity game) {
        Long appId = game.getSteamAppId();
        if (!enabled || appId == null || !imageRepository.findByGameEntityId(game.getId()).isEmpty()) {
            return false;
        }
        download(game, appId, "library_600x900.jpg", ImageType.COVER);
        download(game, appId, "library_hero.jpg", ImageType.BACKGROUND);
        // The images arrive through IMAGE_FOUND; nothing on the entity itself changed.
        return false;
    }

    private void download(GameEntity game, long appId, String file, ImageType type) {
        String url = cdnBase + "/" + appId + "/" + file;
        try {
            imageDownloadService.downloadAndSave(url, type, null, "steam://" + url, ImageSave.MediaEntityRef.ofGame(game));
        } catch (IOException e) {
            // Not every app has every asset (tools, very old or delisted games).
            log.info("No Steam {} for {} ({}): {}", type, game.getName(), appId, e.getMessage());
        }
    }
}
