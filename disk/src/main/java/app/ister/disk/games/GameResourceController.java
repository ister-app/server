package app.ister.disk.games;

import app.ister.core.entity.GameEntity;
import app.ister.core.entity.GameSaveEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.GameAchievementSource;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.UserService;
import app.ister.core.storage.FileAccess;
import app.ister.core.storage.LocalCopy;
import app.ister.core.storage.ObjectStat;
import app.ister.core.storage.PathStrings;
import app.ister.core.utils.LibraryPathValidator;
import app.ister.disk.http.ByteRanges;
import app.ister.disk.http.ByteRanges.Range;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves games to the client and keeps its saves and achievements:
 *
 * <ul>
 *   <li>{@code GET /game/{id}/manifest} — what to download: every file with its size, the entry
 *       to launch, the form/platform. Single-file games list their media files; folder games
 *       (PS3, Steam) are listed live from the game folder (cached per size/file-count).</li>
 *   <li>{@code GET /game/{id}/file/{*relative}} — one file, with HTTP Range so a client can resume.</li>
 *   <li>{@code /game/{id}/saves...} — the calling user's save snapshots: list, upload (raw zip body,
 *       409 on a conflicting newer head), download, restore.</li>
 *   <li>{@code POST /game/{id}/achievements} — the client's report of trophies/achievements.</li>
 * </ul>
 *
 * <p>Auth like the comic/epub endpoints: a bearer token or a {@code ?token=} stream token
 * ({@code /game/} user path). Library visibility is enforced before this controller runs
 * (MediaAccessEnforcementFilter on {@code /game/{gameId}}); saves are additionally per user.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GameResourceController {

    private static final String CACHE_CONTROL_IMMUTABLE = "private, max-age=31536000, immutable";
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final GameRepository gameRepository;
    private final MediaFileRepository mediaFileRepository;
    private final GameTreeLister treeLister;
    private final GameSaveService saveService;
    private final GameAchievementService achievementService;
    private final LibraryAccessService libraryAccessService;
    private final UserService userService;
    private final LocalCopy localCopy;
    private final FileAccess fileAccess;

    /** Folder listings are expensive (tens of thousands of stats); one per (game, size, count). */
    private final Map<String, List<GameFile>> manifestCache = new ConcurrentHashMap<>();

    public record GameFile(String path, long size, @Nullable Instant lastModified, boolean entry) {}

    public record GameManifest(UUID gameId, GamePlatform platform, GameForm form, @Nullable String entryPath,
                               @Nullable String titleId, @Nullable Long steamAppId, @Nullable String steamInstallDir,
                               long totalBytes, int fileCount, List<GameFile> files) {}

    public record GameSaveDto(UUID id, int version, long sizeBytes, String sha256, @Nullable String deviceName,
                              @Nullable String note, Instant createdAt) {
        static GameSaveDto of(GameSaveEntity save) {
            return new GameSaveDto(save.getId(), save.getVersion(), save.getSizeBytes(), save.getSha256(),
                    save.getDeviceName(), save.getNote(), save.getDateCreated());
        }
    }

    public record SaveConflict(String error, int latestVersion, Instant latestCreatedAt, @Nullable String latestDevice) {}

    public record AchievementReport(GameAchievementSource source, @Nullable String deviceName,
                                    List<GameAchievementService.Reported> achievements) {}

    public record AchievementSummary(int total, int unlocked) {}

    // ---- files -------------------------------------------------------------------------------

    @GetMapping("/game/{gameId}/manifest")
    public ResponseEntity<GameManifest> manifest(@PathVariable UUID gameId,
                                                 @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                                 Authentication authentication) throws IOException {
        GameEntity game = visibleGame(gameId, authentication);
        String etag = "\"%s-%d-%d\"".formatted(game.getId(), game.getSizeBytes(), game.getFileCount());
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        List<GameFile> files = filesOf(game);
        long total = files.stream().mapToLong(GameFile::size).sum();
        return ResponseEntity.ok()
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
                .body(new GameManifest(game.getId(), game.getPlatform(), game.getForm(), game.getEntryPath(),
                        game.getTitleId(), game.getSteamAppId(), game.getSteamInstallDir(),
                        total, files.size(), files));
    }

    /** One file of the game by its manifest path, with single-range support for resumable downloads. */
    @GetMapping("/game/{gameId}/file/{*relative}")
    public ResponseEntity<StreamingResponseBody> file(@PathVariable UUID gameId, @PathVariable String relative,
                                                      @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                                                      Authentication authentication) throws IOException {
        GameEntity game = visibleGame(gameId, authentication);
        String relativePath = LibraryPathValidator.requireRelative(relative.startsWith("/") ? relative.substring(1) : relative);
        Optional<Source> source = sourceOf(game, relativePath);
        if (source.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Source src = source.get();
        Range range = ByteRanges.parseRange(rangeHeader, src.size());
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(range != null ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_IMMUTABLE)
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (range != null) {
            return response
                    .contentLength(range.length())
                    .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(range.start(), range.end(), src.size()))
                    .body(output -> {
                        try (InputStream in = src.open()) {
                            ByteRanges.skipFully(in, range.start());
                            ByteRanges.copy(in, output, range.length());
                        }
                    });
        }
        return response.contentLength(src.size()).body(output -> {
            try (InputStream in = src.open()) {
                in.transferTo(output);
            }
        });
    }

    // ---- saves -------------------------------------------------------------------------------

    @GetMapping("/game/{gameId}/saves")
    public List<GameSaveDto> saves(@PathVariable UUID gameId, Authentication authentication) {
        GameEntity game = visibleGame(gameId, authentication);
        UserEntity user = userService.getOrCreateUser(authentication);
        return saveService.list(user, game).stream().map(GameSaveDto::of).toList();
    }

    /**
     * Uploads a snapshot (raw {@code application/zip} body). {@code baseVersion} is the head the
     * client synced from; a newer head answers 409 with the head's details unless {@code force}.
     */
    @PostMapping(value = "/game/{gameId}/saves", consumes = {"application/zip", "application/octet-stream"})
    @PreAuthorize("hasRole('user')")
    public ResponseEntity<Object> uploadSave(@PathVariable UUID gameId,
                                        @RequestParam(required = false) Integer baseVersion,
                                        @RequestParam(defaultValue = "false") boolean force,
                                        @RequestParam(required = false) String device,
                                        @RequestParam(required = false) String note,
                                        @RequestHeader(value = "X-Content-SHA256", required = false) String sha256,
                                        HttpServletRequest request,
                                        Authentication authentication) throws IOException {
        GameEntity game = visibleGame(gameId, authentication);
        UserEntity user = userService.getOrCreateUser(authentication);
        long length = request.getContentLengthLong();
        if (length < 0) {
            return ResponseEntity.status(HttpStatus.LENGTH_REQUIRED).build();
        }
        try (InputStream body = request.getInputStream()) {
            GameSaveEntity saved = saveService.store(user, game, new GameSaveService.Upload(baseVersion, force, device, note, sha256), body, length);
            return ResponseEntity.status(HttpStatus.CREATED).body(GameSaveDto.of(saved));
        } catch (GameSaveService.ConflictException conflict) {
            GameSaveEntity head = conflict.head();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new SaveConflict(
                    conflict.getMessage(), head.getVersion(), head.getDateCreated(), head.getDeviceName()));
        }
    }

    @GetMapping("/game/{gameId}/saves/{version}")
    public ResponseEntity<StreamingResponseBody> downloadSave(@PathVariable UUID gameId, @PathVariable int version,
                                                              @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                                                              Authentication authentication) {
        GameEntity game = visibleGame(gameId, authentication);
        UserEntity user = userService.getOrCreateUser(authentication);
        Optional<GameSaveEntity> save = saveService.version(user, game, version);
        if (save.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        GameSaveEntity entity = save.get();
        Range range = ByteRanges.parseRange(rangeHeader, entity.getSizeBytes());
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(range != null ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_IMMUTABLE)
                .eTag("\"" + entity.getSha256() + "\"")
                .contentType(ZIP);
        if (range != null) {
            response.contentLength(range.length())
                    .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(range.start(), range.end(), entity.getSizeBytes()));
        } else {
            response.contentLength(entity.getSizeBytes());
        }
        return response.body(output -> {
            Optional<InputStream> opened = saveService.open(entity);
            if (opened.isEmpty()) {
                throw new NoSuchElementException("Save " + entity.getId() + " is not on this node");
            }
            try (InputStream in = opened.get()) {
                if (range != null) {
                    ByteRanges.skipFully(in, range.start());
                    ByteRanges.copy(in, output, range.length());
                } else {
                    in.transferTo(output);
                }
            }
        });
    }

    @PostMapping("/game/{gameId}/saves/{version}/restore")
    @PreAuthorize("hasRole('user')")
    public GameSaveDto restoreSave(@PathVariable UUID gameId, @PathVariable int version,
                                   @RequestParam(required = false) String device,
                                   Authentication authentication) throws IOException {
        GameEntity game = visibleGame(gameId, authentication);
        UserEntity user = userService.getOrCreateUser(authentication);
        return GameSaveDto.of(saveService.restore(user, game, version, device));
    }

    @PostMapping("/game/{gameId}/saves/{version}/delete")
    @PreAuthorize("hasRole('user')")
    public ResponseEntity<Void> deleteSave(@PathVariable UUID gameId, @PathVariable int version,
                                           Authentication authentication) {
        GameEntity game = visibleGame(gameId, authentication);
        UserEntity user = userService.getOrCreateUser(authentication);
        Optional<GameSaveEntity> save = saveService.version(user, game, version);
        if (save.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        saveService.delete(save.get());
        return ResponseEntity.noContent().build();
    }

    // ---- achievements ------------------------------------------------------------------------

    @PostMapping(value = "/game/{gameId}/achievements", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('user')")
    public AchievementSummary reportAchievements(@PathVariable UUID gameId, @RequestBody AchievementReport report,
                                                 Authentication authentication) {
        GameEntity game = visibleGame(gameId, authentication);
        UserEntity user = userService.getOrCreateUser(authentication);
        if (report.source() == null || report.achievements() == null) {
            throw new IllegalArgumentException("source and achievements are required");
        }
        GameAchievementService.Summary summary =
                achievementService.report(user, game, report.source(), report.achievements(), report.deviceName());
        return new AchievementSummary(summary.total(), summary.unlocked());
    }

    /** A PNG icon for one achievement definition, raw body. */
    @PostMapping(value = "/game/{gameId}/achievements/{source}/{externalId}/icon", consumes = {"image/png", "application/octet-stream"})
    @PreAuthorize("hasRole('user')")
    public ResponseEntity<Void> achievementIcon(@PathVariable UUID gameId, @PathVariable GameAchievementSource source,
                                                @PathVariable String externalId, HttpServletRequest request,
                                                Authentication authentication) throws IOException {
        GameEntity game = visibleGame(gameId, authentication);
        byte[] png;
        try (InputStream in = request.getInputStream()) {
            png = in.readAllBytes();
        }
        return achievementService.storeIcon(game, source, externalId, png).isPresent()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private GameEntity visibleGame(UUID gameId, Authentication authentication) {
        return gameRepository.findById(gameId)
                .filter(game -> libraryAccessService.canAccess(game.getLibraryEntity(), authentication))
                .orElseThrow(() -> new NoSuchElementException("Game not found: " + gameId));
    }

    private List<GameFile> filesOf(GameEntity game) throws IOException {
        if (!game.getForm().isFolder()) {
            return mediaFileRepository.findByGameEntityId(game.getId()).stream()
                    .map(file -> new GameFile(PathStrings.fileName(file.getPath()), file.getSize(),
                            file.getFileLastModifiedTime(), file.isGameEntryFile()))
                    .sorted(java.util.Comparator.comparing(GameFile::path))
                    .toList();
        }
        String cacheKey = game.getId() + ":" + game.getSizeBytes() + ":" + game.getFileCount();
        List<GameFile> cached = manifestCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String entry = game.getEntryPath();
        List<GameFile> files = treeLister.list(game.getDirectoryEntity(), listedRoot(game)).stream()
                .map(f -> new GameFile(prefixed(game, f.relativePath()), f.size(), f.lastModified(),
                        entry != null && entry.equals(prefixed(game, f.relativePath()))))
                .toList();
        if (game.getForm() == GameForm.STEAM) {
            // The manifest file itself travels along so the client can write it back into a Steam library.
            String acf = steamManifestName(game);
            if (acf != null) {
                files = new java.util.ArrayList<>(files);
                Optional<ObjectStat> stat = fileAccess.stat(game.getDirectoryEntity(), game.getRootPath() + "/" + acf);
                files.add(new GameFile(acf, stat.map(ObjectStat::size).orElse(0L), stat.map(ObjectStat::lastModified).orElse(null), false));
                files = List.copyOf(files);
            }
        }
        manifestCache.keySet().removeIf(key -> key.startsWith(game.getId() + ":") && !key.equals(cacheKey));
        manifestCache.put(cacheKey, files);
        return files;
    }

    /** Steam trees are listed from the install folder; PS3 from the game folder itself. */
    private static String listedRoot(GameEntity game) {
        return game.getForm() == GameForm.STEAM && game.getSteamInstallDir() != null
                ? game.getRootPath() + "/" + game.getSteamInstallDir()
                : game.getRootPath();
    }

    private static String prefixed(GameEntity game, String relativeToListedRoot) {
        return game.getForm() == GameForm.STEAM && game.getSteamInstallDir() != null
                ? game.getSteamInstallDir() + "/" + relativeToListedRoot
                : relativeToListedRoot;
    }

    private static String steamManifestName(GameEntity game) {
        return game.getSteamAppId() == null ? null : "appmanifest_" + game.getSteamAppId() + ".acf";
    }

    private record Source(long size, Opener opener) {
        InputStream open() throws IOException {
            return opener.open();
        }
    }

    private interface Opener {
        InputStream open() throws IOException;
    }

    /** Where a manifest path's bytes come from, or empty when it is not a file of this game. */
    private Optional<Source> sourceOf(GameEntity game, String relativePath) throws IOException {
        if (!game.getForm().isFolder()) {
            return singleFileSource(game, relativePath);
        }
        String full = game.getRootPath() + "/" + relativePath;
        if (game.getDirectoryEntity().isS3()) {
            Optional<ObjectStat> stat = fileAccess.stat(game.getDirectoryEntity(), full);
            return stat.map(s -> new Source(s.size(), () -> fileAccess.open(game.getDirectoryEntity(), full)));
        }
        return localTreeSource(Path.of(game.getRootPath()).toRealPath(), Path.of(full));
    }

    /** A single-file game: the manifest path is the file name of one of its attached rows. */
    private Optional<Source> singleFileSource(GameEntity game, String relativePath) throws IOException {
        for (MediaFileEntity file : mediaFileRepository.findByGameEntityId(game.getId())) {
            if (relativePath.equals(PathStrings.fileName(file.getPath()))) {
                Path local = localCopy.localPathOrNull(file);
                if (local != null && Files.exists(local)) {
                    return Optional.of(new Source(Files.size(local), () -> Files.newInputStream(local)));
                }
                Optional<ObjectStat> stat = fileAccess.stat(file.getDirectoryEntity(), file.getPath());
                return stat.map(s -> new Source(s.size(), () -> fileAccess.open(file.getDirectoryEntity(), file.getPath())));
            }
        }
        return Optional.empty();
    }

    /** A file of a local folder game, resolved through symlinks and confined to the game's folder. */
    private static Optional<Source> localTreeSource(Path rootPath, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        Path real = target.toRealPath();
        if (!real.startsWith(rootPath)) {
            log.warn("Refusing game file outside its folder: {}", target);
            return Optional.empty();
        }
        return Optional.of(new Source(Files.size(real), () -> Files.newInputStream(real)));
    }
}
