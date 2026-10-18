package app.ister.disk.scanner.scanners;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.service.ServerEventService;
import app.ister.core.storage.FileAccess;
import app.ister.disk.games.GameTreeLister;
import app.ister.disk.scanner.GamePathObject;
import app.ister.disk.scanner.enums.FileType;
import app.ister.disk.scanner.steam.VdfParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registers games in a GAME library. Single-file forms (a ROM and its companions, a PC zip, a
 * Linux installer/archive) get a {@link MediaFileEntity} per file attached to the game; folder
 * forms (PS3, Steam) are recognised by their marker file only ({@code PS3_GAME/PARAM.SFO},
 * {@code appmanifest_<appid>.acf}) and recorded as one game with its folder, size and file count —
 * never one row per file. Reading what the files say about the game (PARAM.SFO, save locations)
 * happens asynchronously in the worker's GAME_FOUND handler.
 */
@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class GameScanner implements Scanner {
    private final ScannerHelperService scannerHelperService;
    private final GameRepository gameRepository;
    private final MediaFileRepository mediaFileRepository;
    private final GameTreeLister treeLister;
    private final FileAccess fileAccess;
    private final ServerEventService serverEventService;

    @Override
    public boolean analyzable(String path, boolean isRegularFile, long size) {
        return false; // Games are only parsed relative to a GAME library root.
    }

    /** A game file or a folder-game marker in a GAME library; the rest of a game tree is not scanned. */
    public boolean analyzable(String path, boolean isRegularFile, DirectoryEntity directoryEntity) {
        if (!isRegularFile || directoryEntity.getLibraryEntity() == null
                || directoryEntity.getLibraryEntity().getLibraryType() != LibraryType.GAME) {
            return false;
        }
        FileType fileType = new GamePathObject(directoryEntity.getPath(), path, false).getFileType();
        return fileType == FileType.GAME || fileType == FileType.GAME_MARKER;
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, String path, boolean isRegularFile, long size) {
        GamePathObject gamePath = new GamePathObject(directoryEntity.getPath(), path, false);
        return switch (gamePath.getFileType()) {
            case GAME -> Optional.of(analyzeSingleFile(directoryEntity, gamePath, path, size));
            case GAME_MARKER -> analyzeFolderGame(directoryEntity, gamePath, path);
            default -> Optional.empty();
        };
    }

    private GameEntity analyzeSingleFile(DirectoryEntity directoryEntity, GamePathObject gamePath, String path, long size) {
        LibraryEntity library = directoryEntity.getLibraryEntity();
        GameEntity game = scannerHelperService.getOrCreateGame(
                library, gamePath.getPlatform(), gamePath.getGameName(), gamePath.getPathYear(), gamePath.getForm());
        if (gamePath.isEntryFile() && game.getForm() != gamePath.getForm()) {
            game.setForm(gamePath.getForm());
        }
        Optional<MediaFileEntity> existing = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, path);
        MediaFileEntity file;
        if (existing.isEmpty()) {
            file = MediaFileEntity.builder()
                    .directoryEntityId(directoryEntity.getId())
                    .gameEntity(game)
                    .gameEntryFile(gamePath.isEntryFile())
                    .path(path)
                    .size(size).build();
            mediaFileRepository.save(file);
        } else {
            file = existing.get();
            boolean changed = false;
            if (file.getGameEntity() == null || !file.getGameEntity().getId().equals(game.getId())) {
                log.warn("Fixing wrong game association for {}: now game {} ({})", path, game.getName(), game.getId());
                file.setGameEntity(game);
                changed = true;
            }
            if (file.isGameEntryFile() != gamePath.isEntryFile() || file.getSize() != size) {
                file.setGameEntryFile(gamePath.isEntryFile());
                file.setSize(size);
                changed = true;
            }
            if (changed) {
                mediaFileRepository.save(file);
            }
        }
        refreshSingleFileTotals(game);
        gameRepository.save(game);
        return game;
    }

    /** The game's size and file count follow its attached files. */
    private void refreshSingleFileTotals(GameEntity game) {
        List<MediaFileEntity> files = mediaFileRepository.findByGameEntityId(game.getId());
        game.setSizeBytes(files.stream().mapToLong(MediaFileEntity::getSize).sum());
        game.setFileCount(files.size());
    }

    private Optional<BaseEntity> analyzeFolderGame(DirectoryEntity directoryEntity, GamePathObject gamePath, String markerPath) {
        LibraryEntity library = directoryEntity.getLibraryEntity();
        SteamManifest steam = null;
        if (gamePath.getForm() == GameForm.STEAM) {
            steam = readSteamManifest(directoryEntity, gamePath, markerPath).orElse(null);
            if (steam == null) {
                return Optional.empty(); // Unreadable manifest or missing install folder: logged, not a game yet.
            }
        }
        GameEntity game = scannerHelperService.getOrCreateGame(
                library, gamePath.getPlatform(), gamePath.getGameName(), gamePath.getPathYear(), gamePath.getForm());
        boolean changed = game.getForm() != gamePath.getForm()
                || game.getDirectoryEntity() == null
                || !Objects.equals(game.getRootPath(), gamePath.getGameRootPath());
        game.setForm(gamePath.getForm());
        game.setDirectoryEntity(directoryEntity);
        game.setRootPath(gamePath.getGameRootPath());

        GameTreeLister.TreeStats stats;
        try {
            stats = gamePath.getForm() == GameForm.STEAM
                    ? treeLister.stats(directoryEntity, gamePath.getGameRootPath() + "/" + steam.installDir())
                    : treeLister.stats(directoryEntity, gamePath.getGameRootPath());
        } catch (IOException e) {
            log.warn("Could not size game folder {}: {}", gamePath.getGameRootPath(), e.getMessage());
            stats = new GameTreeLister.TreeStats(game.getSizeBytes(), game.getFileCount());
        }
        if (game.getSizeBytes() != stats.totalBytes() || game.getFileCount() != stats.fileCount()) {
            game.setSizeBytes(stats.totalBytes());
            game.setFileCount(stats.fileCount());
            changed = true;
        }
        if (steam != null) {
            changed |= applySteam(game, steam);
        } else {
            String entryPath = gamePath.ps3EntryPath();
            if (!Objects.equals(game.getEntryPath(), entryPath)) {
                game.setEntryPath(entryPath);
                changed = true;
            }
        }
        gameRepository.save(game);
        if (changed) {
            // A changed tree (re-upload, new build) re-runs the enrichment: the worker re-reads the
            // marker and the manifest; the first creation already queued this from the helper.
            serverEventService.createGameFoundEvent(game.getId());
        }
        return Optional.of(game);
    }

    private boolean applySteam(GameEntity game, SteamManifest steam) {
        boolean changed = !Objects.equals(game.getSteamAppId(), steam.appId())
                || !Objects.equals(game.getSteamInstallDir(), steam.installDir())
                || !Objects.equals(game.getSteamBuildId(), steam.buildId());
        game.setSteamAppId(steam.appId());
        game.setSteamInstallDir(steam.installDir());
        game.setSteamBuildId(steam.buildId());
        if (game.getTitle() == null && steam.name() != null && !steam.name().isBlank()) {
            game.setTitle(steam.name());
            changed = true;
        }
        game.setEntryPath(null);
        return changed;
    }

    record SteamManifest(long appId, String name, String installDir, Long buildId) {}

    /** The .acf next to the install folder; both must be present for the game to count. */
    private Optional<SteamManifest> readSteamManifest(DirectoryEntity directoryEntity, GamePathObject gamePath, String markerPath) {
        Map<String, Object> state;
        try (InputStream in = fileAccess.open(directoryEntity, markerPath)) {
            state = VdfParser.body(VdfParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Unreadable Steam manifest {}: {}", markerPath, e.getMessage());
            return Optional.empty();
        }
        long appId = VdfParser.number(state, "appid").orElse(gamePath.getSteamAppId() == null ? -1L : gamePath.getSteamAppId());
        String installDir = VdfParser.string(state, "installdir").map(String::strip).orElse(null);
        if (appId <= 0 || installDir == null || installDir.isBlank() || installDir.contains("/") || installDir.contains("\\")) {
            log.warn("Steam manifest {} has no usable appid/installdir; skipping", markerPath);
            return Optional.empty();
        }
        if (!treeLister.isNonEmptyDirectory(directoryEntity, gamePath.getGameRootPath(), installDir)) {
            log.warn("Steam manifest {} names install folder '{}' which is missing next to it; skipping",
                    markerPath, installDir);
            return Optional.empty();
        }
        return Optional.of(new SteamManifest(appId,
                VdfParser.string(state, "name").orElse(null),
                installDir,
                VdfParser.number(state, "buildid").orElse(null)));
    }
}
