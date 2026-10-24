package app.ister.worker.events.gamefound;

import app.ister.core.Handle;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.GameFoundData;
import app.ister.core.games.GameSavePaths;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.service.ServerEventService;
import app.ister.core.status.ActivityContext;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import app.ister.core.storage.FileAccess;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_GAME_FOUND;

/**
 * Enriches a new or changed game from what its files say: a PS3 game's title, title id and icon
 * come out of {@code PARAM.SFO} / {@code ICON0.PNG}; PC and Steam games get their save locations
 * from the Ludusavi manifest; the pluggable {@link GameMetadataProvider} may add more. Local
 * facts win: a cover scanned from the game folder is never replaced, and an admin's save paths
 * are kept next to the manifest's.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HandleGameFound implements Handle<GameFoundData> {

    private static final long MAX_SFO_BYTES = 1L << 20;
    private static final long MAX_ICON_BYTES = 4L << 20;

    private final GameRepository gameRepository;
    private final ImageRepository imageRepository;
    private final FileAccess fileAccess;
    private final CacheDirectoryResolver cacheDirectoryResolver;
    private final ImageSave imageSave;
    private final LudusaviManifestService ludusavi;
    private final GameMetadataProvider metadataProvider;
    private final ServerEventService serverEventService;

    @Override
    public EventType handles() {
        return EventType.GAME_FOUND;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_GAME_FOUND)
    @Override
    public void listener(GameFoundData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(GameFoundData data) {
        gameRepository.findById(data.getGameId()).ifPresent(game -> {
            ActivityContext.subject(game.displayTitle());
            boolean changed = false;
            if (game.getForm() == GameForm.PS3_FOLDER) {
                changed = readParamSfo(game);
                saveIconIfNoArtwork(game);
            }
            if (usesLudusavi(game.getForm())) {
                changed |= resolveSavePaths(game);
            }
            changed |= metadataProvider.enrich(game);
            if (changed) {
                gameRepository.save(game);
                serverEventService.createSearchIndexEvent(SearchEntityType.GAME, game.getId());
            }
        });
    }

    private static boolean usesLudusavi(GameForm form) {
        return form == GameForm.STEAM || form == GameForm.PC_PORTABLE_ZIP
                || form == GameForm.PC_LINUX_INSTALLER || form == GameForm.PC_LINUX_PORTABLE;
    }

    /** TITLE and TITLE_ID from PARAM.SFO; the folder name stays the identity, the title the display. */
    private boolean readParamSfo(GameEntity game) {
        Optional<String> sfoPath = ps3File(game, "PARAM.SFO");
        if (sfoPath.isEmpty()) {
            return false;
        }
        Map<String, Object> entries;
        try (InputStream in = fileAccess.open(game.getDirectoryEntity(), sfoPath.get())) {
            entries = ParamSfoParser.parse(in.readNBytes((int) MAX_SFO_BYTES));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Unreadable PARAM.SFO for {}: {}", game.getName(), e.getMessage());
            return false;
        }
        boolean changed = false;
        Optional<String> title = ParamSfoParser.title(entries);
        if (title.isPresent() && !Objects.equals(game.getTitle(), title.get())) {
            game.setTitle(title.get());
            changed = true;
        }
        Optional<String> titleId = ParamSfoParser.string(entries, "TITLE_ID");
        if (titleId.isPresent() && !Objects.equals(game.getTitleId(), titleId.get())) {
            game.setTitleId(titleId.get());
            changed = true;
        }
        return changed;
    }

    /** ICON0.PNG becomes the cover when the game folder has no artwork of its own. */
    private void saveIconIfNoArtwork(GameEntity game) {
        if (!imageRepository.findByGameEntityId(game.getId()).isEmpty()) {
            return;
        }
        Optional<String> iconPath = ps3File(game, "ICON0.PNG");
        if (iconPath.isEmpty()) {
            return;
        }
        DirectoryEntity directory = game.getDirectoryEntity();
        if (!fileAccess.exists(directory, iconPath.get())) {
            return;
        }
        try (InputStream in = fileAccess.open(directory, iconPath.get())) {
            byte[] png = in.readNBytes((int) MAX_ICON_BYTES);
            CacheStore store = cacheDirectoryResolver.store();
            String storedPath = store.write(String.join("/", "games", game.getId().toString(), UUID.randomUUID() + ".png"), png, "image/png");
            imageSave.save(store.directory(), storedPath, ImageType.COVER, null,
                    "ps3sfo://" + game.getId() + "/ICON0.PNG", ImageSave.MediaEntityRef.ofGame(game));
        } catch (IOException e) {
            log.warn("Could not copy ICON0.PNG for {}: {}", game.getName(), e.getMessage());
        }
    }

    /** {@code <root>/[wrapper/]PS3_GAME/<name>}, derived from the entry path the scanner recorded. */
    private static Optional<String> ps3File(GameEntity game, String name) {
        if (game.getDirectoryEntity() == null || game.getRootPath() == null || game.getEntryPath() == null) {
            return Optional.empty();
        }
        int marker = game.getEntryPath().indexOf("PS3_GAME/");
        if (marker < 0) {
            return Optional.empty();
        }
        String prefix = game.getEntryPath().substring(0, marker);
        return Optional.of(game.getRootPath() + "/" + prefix + "PS3_GAME/" + name);
    }

    private boolean resolveSavePaths(GameEntity game) {
        LudusaviManifestService.Match match = ludusavi.find(game.getSteamAppId(), game.displayTitle());
        if (match.matchedBy() == LudusaviManifestService.MatchedBy.NONE && game.getTitle() != null) {
            match = ludusavi.find(null, game.getName()); // the folder name may be the cleaner title
        }
        String json = match.entry() == null ? null : GameSavePaths.toJson(match.entry().savePaths());
        boolean cloud = match.entry() != null && match.entry().steamCloud();
        String matchedBy = match.matchedBy().name();
        boolean changed = !Objects.equals(game.getManifestSavePaths(), json)
                || game.isSteamCloud() != cloud
                || !Objects.equals(game.getManifestMatchedBy(), matchedBy);
        game.setManifestSavePaths(json);
        game.setSteamCloud(cloud);
        game.setManifestMatchedBy(matchedBy);
        return changed;
    }
}
