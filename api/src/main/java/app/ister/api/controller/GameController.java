package app.ister.api.controller;

import app.ister.core.entity.GameAchievementEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.GameSaveEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserGameAchievementEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.GameAchievementSource;
import app.ister.core.enums.GameLauncherKind;
import app.ister.core.enums.GamePlatform;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.games.GameSavePaths;
import app.ister.core.games.GameSavePaths.SavePath;
import app.ister.core.repository.GameAchievementRepository;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.GameSaveRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.UserGameAchievementRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.ContinueWatchingService;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.UserService;
import app.ister.core.service.WatchStatusService;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import app.ister.core.storage.FileAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GraphQL side of games: browsing, launch hints, save paths and the per-user play/save/achievement
 * state. The binary traffic (manifest, files, save zips, achievement icons) is REST in the disk
 * module ({@code GameResourceController}); the two share the entities and the cache store.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class GameController {
    private final GameRepository gameRepository;
    private final GameSaveRepository gameSaveRepository;
    private final GameAchievementRepository achievementRepository;
    private final UserGameAchievementRepository userAchievementRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final WatchStatusService watchStatusService;
    private final ContinueWatchingService continueWatchingService;
    private final UserService userService;
    private final LibraryAccessService libraryAccessService;
    private final CacheDirectoryResolver cacheDirectoryResolver;
    private final FileAccess fileAccess;

    public record GameLauncherHint(GameLauncherKind kind, @Nullable String retroArchCore) {
        static GameLauncherHint of(GamePlatform platform) {
            return new GameLauncherHint(platform.launcher(), platform.retroArchCore());
        }
    }

    public record GamePlatformInfo(GamePlatform platform, List<String> folderNames, List<String> entryExtensions,
                                   List<String> companionExtensions, GameLauncherHint launcher) {}

    public record GameAchievementSummary(int total, int unlocked, List<GameAchievementView> list) {}

    public record GameAchievementView(UUID id, GameAchievementSource source, String externalId, String name, @Nullable String description,
                                      @Nullable String grade, boolean hidden, @Nullable ImageEntity icon,
                                      @Nullable String unlockedAt, GameEntity game) {}

    // ---- queries -----------------------------------------------------------------------------

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<GameEntity> gameById(@Argument UUID id, Authentication authentication) {
        return gameRepository.findById(id)
                .filter(game -> libraryAccessService.canAccess(game.getLibraryEntity(), authentication));
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<GameEntity> games(@Argument Optional<Integer> page,
                                  @Argument Optional<Integer> size,
                                  @Argument Optional<SortingEnum> sorting,
                                  @Argument Optional<SortingOrder> sortingOrder,
                                  @Argument Optional<UUID> libraryId,
                                  @Argument Optional<GamePlatform> platform,
                                  Authentication authentication) {
        Pageable pageable = Paging.pageable(page, size, 20,
                sorting, SortingEnum.NAME, sortingOrder, SortingOrder.ASCENDING);
        if (libraryId.isPresent()) {
            UUID id = libraryId.get();
            if (!libraryAccessService.canAccess(id, authentication)) {
                return Page.empty(pageable);
            }
            return platform.map(p -> gameRepository.findByLibraryEntityIdAndPlatform(id, p, pageable))
                    .orElseGet(() -> gameRepository.findByLibraryEntityId(id, pageable));
        }
        Optional<java.util.Set<UUID>> allowed = libraryAccessService.allowedLibraryIds(authentication);
        if (allowed.isPresent()) {
            return platform.map(p -> gameRepository.findByLibraryEntityIdInAndPlatform(allowed.get(), p, pageable))
                    .orElseGet(() -> gameRepository.findByLibraryEntityIdIn(allowed.get(), pageable));
        }
        return platform.map(p -> gameRepository.findByPlatform(p, pageable))
                .orElseGet(() -> gameRepository.findAll(pageable));
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<GamePlatformInfo> gamePlatforms() {
        return Arrays.stream(GamePlatform.values())
                .map(p -> new GamePlatformInfo(p, p.folderNames(), p.entryExtensions(), p.companionExtensions(),
                        GameLauncherHint.of(p)))
                .toList();
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    @Transactional(readOnly = true)
    public List<GameAchievementView> recentAchievements(@Argument Optional<Integer> limit, Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        int size = Math.clamp(limit.orElse(20), 1, 100);
        return userAchievementRepository.findRecentByUser(user.getId(), PageRequest.of(0, size)).stream()
                .filter(u -> libraryAccessService.canAccess(u.getGameAchievementEntity().getGameEntity().getLibraryEntity(), authentication))
                .map(u -> view(u.getGameAchievementEntity(), u.getUnlockedAt()))
                .toList();
    }

    // ---- mutations ---------------------------------------------------------------------------

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    @Transactional
    public WatchStatusEntity recordGamePlayed(@Argument UUID gameId, @Argument int durationMs, Authentication authentication) {
        GameEntity game = visibleGame(gameId, authentication);
        UserEntity user = userService.getOrCreateUser(authentication);
        WatchStatusEntity status = watchStatusService.getOrCreateForGame(user, game);
        status.setPlayTimeMs(status.getPlayTimeMs() + Math.max(0, durationMs));
        status.setProgressInMilliseconds(status.getPlayTimeMs());
        status.setDateUpdated(Instant.now());
        watchStatusRepository.save(status);
        continueWatchingService.onWatchStatusChanged(status);
        return status;
    }

    @PreAuthorize("hasRole('admin')")
    @MutationMapping
    @Transactional
    public GameEntity updateGameSavePaths(@Argument UUID gameId, @Argument List<String> savePaths) {
        GameEntity game = gameRepository.findById(gameId)
                .orElseThrow(() -> new NoSuchElementException("Game not found: " + gameId));
        List<SavePath> cleaned = savePaths.stream()
                .map(String::strip)
                .filter(p -> !p.isEmpty())
                .map(p -> new SavePath(p, null))
                .toList();
        game.setSavePaths(cleaned.isEmpty() ? null : GameSavePaths.toJson(cleaned));
        return gameRepository.save(game);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public GameSaveEntity restoreGameSave(@Argument UUID gameId, @Argument int version, Authentication authentication) throws IOException {
        GameEntity game = visibleGame(gameId, authentication);
        UserEntity user = userService.getOrCreateUser(authentication);
        GameSaveEntity source = gameSaveRepository.findByUserEntityIdAndGameEntityIdAndVersion(user.getId(), game.getId(), version)
                .orElseThrow(() -> new NoSuchElementException("No save version " + version));
        int next = gameSaveRepository.findFirstByUserEntityIdAndGameEntityIdOrderByVersionDesc(user.getId(), game.getId())
                .map(GameSaveEntity::getVersion).orElse(0) + 1;
        CacheStore store = cacheDirectoryResolver.store();
        String storedPath;
        try (InputStream in = fileAccess.open(source.getCacheDirectoryEntity(), source.getStoredPath())) {
            storedPath = store.write("saves/" + user.getId() + "/" + game.getId() + "/" + next + ".zip",
                    in, source.getSizeBytes(), "application/zip");
        }
        return gameSaveRepository.save(GameSaveEntity.builder()
                .userEntity(user)
                .gameEntity(game)
                .version(next)
                .cacheDirectoryEntity(store.directory())
                .storedPath(storedPath)
                .sizeBytes(source.getSizeBytes())
                .sha256(source.getSha256())
                .note("restored from v" + version)
                .build());
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean deleteGameSave(@Argument UUID gameId, @Argument int version, Authentication authentication) {
        GameEntity game = visibleGame(gameId, authentication);
        UserEntity user = userService.getOrCreateUser(authentication);
        Optional<GameSaveEntity> save = gameSaveRepository.findByUserEntityIdAndGameEntityIdAndVersion(user.getId(), game.getId(), version);
        if (save.isEmpty()) {
            return false;
        }
        try {
            fileAccess.delete(save.get().getCacheDirectoryEntity(), save.get().getStoredPath());
        } catch (IOException e) {
            log.warn("Could not delete save object {}: {}", save.get().getStoredPath(), e.getMessage());
        }
        gameSaveRepository.delete(save.get());
        return true;
    }

    // ---- Game fields -------------------------------------------------------------------------

    @SchemaMapping(typeName = "Game", field = "title")
    public String title(GameEntity game) {
        return game.displayTitle();
    }

    @SchemaMapping(typeName = "Game", field = "steamAppId")
    public String steamAppId(GameEntity game) {
        return game.getSteamAppId() == null ? null : game.getSteamAppId().toString();
    }

    @SchemaMapping(typeName = "Game", field = "launcher")
    public GameLauncherHint launcher(GameEntity game) {
        return GameLauncherHint.of(game.getPlatform());
    }

    @SchemaMapping(typeName = "Game", field = "savePaths")
    public List<SavePath> savePaths(GameEntity game) {
        List<SavePath> override = GameSavePaths.fromJson(game.getSavePaths());
        return override.isEmpty() ? GameSavePaths.fromJson(game.getManifestSavePaths()) : override;
    }

    @SchemaMapping(typeName = "Game", field = "savePathSource")
    public String savePathSource(GameEntity game) {
        if (!GameSavePaths.fromJson(game.getSavePaths()).isEmpty()) {
            return "ADMIN";
        }
        return GameSavePaths.fromJson(game.getManifestSavePaths()).isEmpty() ? "NONE" : "MANIFEST";
    }

    @SchemaMapping(typeName = "Game", field = "savePathOverride")
    public List<SavePath> savePathOverride(GameEntity game) {
        return GameSavePaths.fromJson(game.getSavePaths());
    }

    @SchemaMapping(typeName = "Game", field = "library")
    public LibraryEntity library(GameEntity game) {
        return game.getLibraryEntity();
    }

    @BatchMapping(typeName = "Game", field = "images")
    public Map<GameEntity, List<ImageEntity>> images(List<GameEntity> games) {
        Map<UUID, List<ImageEntity>> byGame = imageRepository.findByGameEntityIdIn(ids(games)).stream()
                .collect(Collectors.groupingBy(ImageEntity::getGameEntityId));
        return toLinkedMap(games, g -> byGame.getOrDefault(g.getId(), List.of()));
    }

    @SchemaMapping(typeName = "Game", field = "metadata")
    public List<MetadataEntity> metadata(GameEntity game) {
        return metadataRepository.findByGameEntityId(game.getId());
    }

    @BatchMapping(typeName = "Game", field = "lastPlayed")
    public Map<GameEntity, String> lastPlayed(List<GameEntity> games, Authentication authentication) {
        Map<UUID, WatchStatusEntity> byGame = statuses(games, authentication);
        return toLinkedMap(games, g -> {
            WatchStatusEntity status = byGame.get(g.getId());
            return status == null ? null : status.getDateUpdated().toString();
        });
    }

    @BatchMapping(typeName = "Game", field = "playTimeMs")
    public Map<GameEntity, Long> playTimeMs(List<GameEntity> games, Authentication authentication) {
        Map<UUID, WatchStatusEntity> byGame = statuses(games, authentication);
        return toLinkedMap(games, g -> {
            WatchStatusEntity status = byGame.get(g.getId());
            return status == null ? 0L : status.getPlayTimeMs();
        });
    }

    @SchemaMapping(typeName = "Game", field = "saves")
    public List<GameSaveEntity> saves(GameEntity game, Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return gameSaveRepository.findByUserEntityIdAndGameEntityIdOrderByVersionDesc(user.getId(), game.getId());
    }

    @SchemaMapping(typeName = "Game", field = "achievements")
    @Transactional(readOnly = true)
    public GameAchievementSummary achievements(GameEntity game, Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        List<GameAchievementEntity> definitions = achievementRepository.findByGameEntityIdOrderBySortOrderAscNameAsc(game.getId());
        Map<UUID, Instant> unlockedAt = new HashMap<>();
        for (UserGameAchievementEntity unlock : userAchievementRepository.findByUserAndGame(user.getId(), game.getId())) {
            unlockedAt.put(unlock.getGameAchievementEntity().getId(), unlock.getUnlockedAt());
        }
        List<GameAchievementView> list = new ArrayList<>();
        for (GameAchievementEntity definition : definitions) {
            list.add(view(definition, unlockedAt.get(definition.getId())));
        }
        return new GameAchievementSummary(definitions.size(), unlockedAt.size(), list);
    }

    // ---- GameSave fields ---------------------------------------------------------------------

    @SchemaMapping(typeName = "GameSave", field = "createdAt")
    public String createdAt(GameSaveEntity save) {
        return save.getDateCreated().toString();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static GameAchievementView view(GameAchievementEntity definition, @Nullable Instant unlockedAt) {
        return new GameAchievementView(definition.getId(), definition.getSource(), definition.getExternalId(),
                definition.getName(), definition.getDescription(), definition.getGrade(), definition.isHidden(),
                definition.getIconImageEntity(), unlockedAt == null ? null : unlockedAt.toString(),
                definition.getGameEntity());
    }

    private Map<UUID, WatchStatusEntity> statuses(List<GameEntity> games, Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return watchStatusRepository.findByUserEntityAndGameEntityIn(user, games).stream()
                .collect(Collectors.toMap(s -> s.getGameEntity().getId(), Function.identity(), (a, b) -> a));
    }

    private GameEntity visibleGame(UUID gameId, Authentication authentication) {
        return gameRepository.findById(gameId)
                .filter(game -> libraryAccessService.canAccess(game.getLibraryEntity(), authentication))
                .orElseThrow(() -> new NoSuchElementException("Game not found: " + gameId));
    }

    private static List<UUID> ids(List<GameEntity> games) {
        return games.stream().map(GameEntity::getId).toList();
    }

    /** Batch results keyed in input order; unlike Collectors.toMap this allows null values (no last-played yet). */
    private static <V> Map<GameEntity, V> toLinkedMap(List<GameEntity> games, Function<GameEntity, V> value) {
        Map<GameEntity, V> result = new LinkedHashMap<>();
        for (GameEntity game : games) {
            result.put(game, value.apply(game));
        }
        return result;
    }
}
