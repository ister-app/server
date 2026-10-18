package app.ister.disk.games;

import app.ister.core.config.GameProperties;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.GameSaveEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.repository.GameSaveRepository;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import app.ister.core.storage.FileAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The per-user save cloud. A client uploads one zip per play session; the snapshot becomes the
 * next version for (user, game), older versions beyond the retention are removed. The upload is
 * "short transaction → stream to a temp file → short transaction": no database connection is held
 * while a client is still sending, like the admin upload's chunks.
 *
 * <p>Conflicts are detected by the client's base version: when the head on the server moved past
 * what the client started from, another device played in between and the client must choose
 * ({@link ConflictException}); {@code force} overrides, and the superseded head stays in history.
 */
@Slf4j
@Service
public class GameSaveService {

    /** Another device wrote a newer snapshot since the client's base version. */
    public static class ConflictException extends RuntimeException {
        private final transient GameSaveEntity head;

        public ConflictException(GameSaveEntity head) {
            super("A newer save (version " + head.getVersion() + ") exists on the server");
            this.head = head;
        }

        public GameSaveEntity head() {
            return head;
        }
    }

    private final GameSaveRepository gameSaveRepository;
    private final CacheDirectoryResolver cacheDirectoryResolver;
    private final FileAccess fileAccess;
    private final GameProperties properties;
    private final TransactionTemplate transaction;
    private final String tmpDir;

    public GameSaveService(GameSaveRepository gameSaveRepository, CacheDirectoryResolver cacheDirectoryResolver,
                           FileAccess fileAccess, GameProperties properties,
                           PlatformTransactionManager transactionManager,
                           @Value("${app.ister.server.tmp-dir}") String tmpDir) {
        this.gameSaveRepository = gameSaveRepository;
        this.cacheDirectoryResolver = cacheDirectoryResolver;
        this.fileAccess = fileAccess;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.tmpDir = tmpDir;
    }

    public List<GameSaveEntity> list(UserEntity user, GameEntity game) {
        return gameSaveRepository.findByUserEntityIdAndGameEntityIdOrderByVersionDesc(user.getId(), game.getId());
    }

    public Optional<GameSaveEntity> head(UserEntity user, GameEntity game) {
        return gameSaveRepository.findFirstByUserEntityIdAndGameEntityIdOrderByVersionDesc(user.getId(), game.getId());
    }

    public Optional<GameSaveEntity> version(UserEntity user, GameEntity game, int version) {
        return gameSaveRepository.findByUserEntityIdAndGameEntityIdAndVersion(user.getId(), game.getId(), version);
    }

    /**
     * What the client tells us about an uploaded snapshot.
     *
     * @param baseVersion the head version the client synced from (0 = none); null skips the check
     * @param force       accept even when the head moved past {@code baseVersion}
     * @param expectedSha the client's sha-256 of the body (hex), or null
     */
    public record Upload(Integer baseVersion, boolean force, String deviceName, String note, String expectedSha) {}

    /** Stores a snapshot streamed from the client as the next version. */
    public GameSaveEntity store(UserEntity user, GameEntity game, Upload upload,
                                InputStream body, long contentLength) throws IOException {
        if (contentLength > properties.getSaveMaxBytes().toBytes()) {
            throw new IllegalArgumentException("Save snapshot larger than " + properties.getSaveMaxBytes());
        }
        checkConflict(user, game, upload.baseVersion(), upload.force());

        Files.createDirectories(Path.of(tmpDir));
        Path staged = Files.createTempFile(Path.of(tmpDir), "gamesave-", ".zip");
        try {
            String sha = copyAndHash(body, staged, properties.getSaveMaxBytes().toBytes());
            if (upload.expectedSha() != null && !upload.expectedSha().equalsIgnoreCase(sha)) {
                throw new IllegalArgumentException("Save snapshot checksum mismatch");
            }
            long size = Files.size(staged);
            // The head may have moved while the body streamed in: check again before claiming a version.
            checkConflict(user, game, upload.baseVersion(), upload.force());
            int version = head(user, game).map(GameSaveEntity::getVersion).orElse(0) + 1;
            CacheStore store = cacheDirectoryResolver.store();
            String storedPath = store.write(key(user, game, version), staged, "application/zip");
            GameSaveEntity saved = transaction.execute(_ -> gameSaveRepository.save(GameSaveEntity.builder()
                    .userEntity(user)
                    .gameEntity(game)
                    .version(version)
                    .cacheDirectoryEntity(store.directory())
                    .storedPath(storedPath)
                    .sizeBytes(size)
                    .sha256(sha)
                    .deviceName(trim(upload.deviceName()))
                    .note(trim(upload.note()))
                    .build()));
            trim(user, game);
            return saved;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** Copies an existing version to a new head version, so the history stays linear. */
    public GameSaveEntity restore(UserEntity user, GameEntity game, int version, String deviceName) throws IOException {
        GameSaveEntity source = version(user, game, version)
                .orElseThrow(() -> new java.util.NoSuchElementException("No save version " + version));
        int next = head(user, game).map(GameSaveEntity::getVersion).orElse(0) + 1;
        CacheStore store = cacheDirectoryResolver.store();
        String storedPath;
        try (InputStream in = fileAccess.open(source.getCacheDirectoryEntity(), source.getStoredPath())) {
            storedPath = store.write(key(user, game, next), in, source.getSizeBytes(), "application/zip");
        }
        GameSaveEntity saved = transaction.execute(_ -> gameSaveRepository.save(GameSaveEntity.builder()
                .userEntity(user)
                .gameEntity(game)
                .version(next)
                .cacheDirectoryEntity(store.directory())
                .storedPath(storedPath)
                .sizeBytes(source.getSizeBytes())
                .sha256(source.getSha256())
                .deviceName(trim(deviceName))
                .note("restored from v" + version)
                .build()));
        trim(user, game);
        return saved;
    }

    /** Opens a stored snapshot; empty when the zip sits on another node's local cache. */
    public Optional<InputStream> open(GameSaveEntity save) {
        try {
            if (!fileAccess.exists(save.getCacheDirectoryEntity(), save.getStoredPath())) {
                return Optional.empty();
            }
            return Optional.of(fileAccess.open(save.getCacheDirectoryEntity(), save.getStoredPath()));
        } catch (IOException e) {
            log.warn("Cannot open save {}: {}", save.getStoredPath(), e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(GameSaveEntity save) {
        deleteObject(save);
        transaction.executeWithoutResult(_ -> gameSaveRepository.delete(save));
    }

    /** Every snapshot of a game (all users), when the game itself goes away. */
    public void deleteAllForGame(UUID gameId) {
        gameSaveRepository.findByGameEntityId(gameId).forEach(this::delete);
    }

    private void checkConflict(UserEntity user, GameEntity game, Integer baseVersion, boolean force) {
        if (force || baseVersion == null) {
            return;
        }
        head(user, game).filter(h -> h.getVersion() > baseVersion).ifPresent(h -> {
            throw new ConflictException(h);
        });
    }

    /** Oldest versions beyond the retention go; the object first, so a failed delete never leaves an orphan. */
    private void trim(UserEntity user, GameEntity game) {
        List<GameSaveEntity> all = list(user, game);
        for (int i = properties.getSaveRetention(); i < all.size(); i++) {
            delete(all.get(i));
        }
    }

    private void deleteObject(GameSaveEntity save) {
        try {
            fileAccess.delete(save.getCacheDirectoryEntity(), save.getStoredPath());
        } catch (IOException e) {
            log.warn("Could not delete save object {}: {}", save.getStoredPath(), e.getMessage());
        }
    }

    private static String key(UserEntity user, GameEntity game, int version) {
        return String.join("/", "saves", user.getId().toString(), game.getId().toString(), version + ".zip");
    }

    private static String copyAndHash(InputStream body, Path target, long maxBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long copied = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new DigestInputStream(body, digest);
             OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                copied += read;
                if (copied > maxBytes) {
                    throw new IllegalArgumentException("Save snapshot larger than " + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() > 255 ? stripped.substring(0, 255) : stripped;
    }
}
