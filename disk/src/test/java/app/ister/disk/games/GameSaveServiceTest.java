package app.ister.disk.games;

import app.ister.core.config.GameProperties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.GameSaveEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.repository.GameSaveRepository;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.FileAccess;
import app.ister.core.storage.ObjectStoreRegistry;
import app.ister.disk.storage.CacheStoreMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The save cloud against a real local cache directory and an in-memory repository: versions count
 * up, conflicts are detected on the client's base version, the retention trims the oldest zips.
 */
class GameSaveServiceTest {

    @TempDir
    Path tempDir;

    private final GameSaveRepository repository = mock(GameSaveRepository.class);
    private final GameProperties properties = new GameProperties();
    private final List<GameSaveEntity> rows = new ArrayList<>();
    private final UserEntity user = UserEntity.builder().name("alice").build();
    private final GameEntity game = GameEntity.builder().name("Chrono").build();
    private DirectoryEntity cacheDir;
    private Path tmpDir;
    private GameSaveService service;

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        game.setId(UUID.randomUUID());
        cacheDir = DirectoryEntity.builder().name("cache").path(tempDir.resolve("cache").toString())
                .directoryType(DirectoryType.CACHE).build();
        cacheDir.setId(UUID.randomUUID());
        tmpDir = tempDir.resolve("tmp");

        CacheDirectoryResolver resolver = mock(CacheDirectoryResolver.class);
        when(resolver.forThisNode()).thenReturn(cacheDir);
        CacheStoreMocks.realLocal(resolver);

        // An in-memory repository: saved rows get an id and are found back by version.
        when(repository.save(any())).thenAnswer(inv -> {
            GameSaveEntity row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                rows.add(row);
            }
            return row;
        });
        when(repository.findByUserEntityIdAndGameEntityIdOrderByVersionDesc(user.getId(), game.getId()))
                .thenAnswer(_ -> sorted().toList());
        when(repository.findFirstByUserEntityIdAndGameEntityIdOrderByVersionDesc(user.getId(), game.getId()))
                .thenAnswer(_ -> sorted().findFirst());
        when(repository.findByUserEntityIdAndGameEntityIdAndVersion(any(), any(), any(Integer.class)))
                .thenAnswer(inv -> rows.stream().filter(r -> r.getVersion() == (int) inv.getArgument(2)).findFirst());
        when(repository.findByGameEntityId(game.getId())).thenAnswer(_ -> List.copyOf(rows));
        doAnswer(inv -> rows.remove((GameSaveEntity) inv.getArgument(0)))
                .when(repository).delete(any());

        service = new GameSaveService(repository, resolver, new FileAccess(mock(ObjectStoreRegistry.class)),
                properties, mock(PlatformTransactionManager.class), tmpDir.toString());
    }

    private Stream<GameSaveEntity> sorted() {
        return rows.stream().sorted(Comparator.comparingInt(GameSaveEntity::getVersion).reversed());
    }

    private static InputStream body(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private GameSaveEntity store(String content, Integer baseVersion, boolean force) throws IOException {
        return service.store(user, game, new GameSaveService.Upload(baseVersion, force, "desk", null, null),
                body(content), content.length());
    }

    @Test
    void storesTheFirstSnapshotAsVersionOneInTheCacheStore() throws Exception {
        GameSaveEntity saved = service.store(user, game,
                new GameSaveService.Upload(0, false, "  desk  ", "   ", sha256("zip-bytes").toUpperCase()),
                body("zip-bytes"), 9);

        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getSizeBytes()).isEqualTo(9);
        assertThat(saved.getSha256()).isEqualTo(sha256("zip-bytes"));
        assertThat(saved.getDeviceName()).isEqualTo("desk");
        assertThat(saved.getNote()).isNull();
        assertThat(saved.getCacheDirectoryEntity()).isSameAs(cacheDir);
        assertThat(saved.getStoredPath())
                .isEqualTo(cacheDir.getPath() + "/saves/" + user.getId() + "/" + game.getId() + "/1.zip");
        assertThat(Path.of(saved.getStoredPath())).hasContent("zip-bytes");
        try (Stream<Path> staged = Files.list(tmpDir)) {
            assertThat(staged).as("staged temp file is removed").isEmpty();
        }
    }

    @Test
    void theNextSnapshotBecomesTheNextVersion() throws Exception {
        store("v1", 0, false);
        GameSaveEntity second = store("v2", 1, false);

        assertThat(second.getVersion()).isEqualTo(2);
        assertThat(service.list(user, game)).extracting(GameSaveEntity::getVersion).containsExactly(2, 1);
        assertThat(service.head(user, game)).map(GameSaveEntity::getVersion).contains(2);
    }

    @Test
    void aNewerHeadThanTheClientsBaseVersionIsAConflict() throws Exception {
        GameSaveEntity head = store("v1", 0, false);

        assertThatThrownBy(() -> store("stale", 0, false))
                .isInstanceOf(GameSaveService.ConflictException.class)
                .satisfies(e -> assertThat(((GameSaveService.ConflictException) e).head()).isSameAs(head))
                .hasMessageContaining("version 1");
        assertThat(rows).hasSize(1);
        assertThat(Path.of(cacheDir.getPath(), "saves", user.getId().toString(), game.getId().toString(), "2.zip")).doesNotExist();
    }

    @Test
    void forceAndANullBaseVersionSkipTheConflictCheck() throws Exception {
        store("v1", 0, false);

        assertThat(store("forced", 0, true).getVersion()).isEqualTo(2);
        assertThat(store("unchecked", null, false).getVersion()).isEqualTo(3);
    }

    @Test
    void rejectsADeclaredLengthAboveTheLimitBeforeReadingTheBody() {
        properties.setSaveMaxBytes(DataSize.ofBytes(4));

        assertThatThrownBy(() -> store("too-long", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger than");
        assertThat(tmpDir).doesNotExist();
    }

    @Test
    void rejectsABodyThatOutgrowsItsDeclaredLength() {
        properties.setSaveMaxBytes(DataSize.ofBytes(4));

        GameSaveService.Upload upload = new GameSaveService.Upload(null, false, null, null, null);
        InputStream oversized = body("way more than four");
        assertThatThrownBy(() -> service.store(user, game, upload, oversized, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger than 4 bytes");
        assertThat(rows).isEmpty();
        assertThat(tmpDir).isEmptyDirectory();
    }

    @Test
    void rejectsAChecksumMismatch() {
        GameSaveService.Upload upload = new GameSaveService.Upload(null, false, null, null, "00ff");
        InputStream zip = body("zip");
        assertThatThrownBy(() -> service.store(user, game, upload, zip, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
        assertThat(rows).isEmpty();
        assertThat(tmpDir).isEmptyDirectory();
    }

    @Test
    void longDeviceNamesAreTruncatedTo255Characters() throws Exception {
        String device = "x".repeat(300);

        GameSaveEntity saved = service.store(user, game, new GameSaveService.Upload(null, false, device, null, null),
                body("zip"), 3);

        assertThat(saved.getDeviceName()).hasSize(255);
    }

    @Test
    void retentionTrimsTheOldestVersionsAndTheirObjects() throws Exception {
        properties.setSaveRetention(2);
        GameSaveEntity first = store("v1", null, false);
        store("v2", null, false);
        store("v3", null, false);

        assertThat(service.list(user, game)).extracting(GameSaveEntity::getVersion).containsExactly(3, 2);
        assertThat(Path.of(first.getStoredPath())).doesNotExist();
        verify(repository).delete(first);
    }

    @Test
    void restoreCopiesAnOldVersionToANewHead() throws Exception {
        GameSaveEntity first = store("old-save", null, false);
        store("newer", null, false);

        GameSaveEntity restored = service.restore(user, game, 1, "laptop");

        assertThat(restored.getVersion()).isEqualTo(3);
        assertThat(restored.getNote()).isEqualTo("restored from v1");
        assertThat(restored.getDeviceName()).isEqualTo("laptop");
        assertThat(restored.getSha256()).isEqualTo(first.getSha256());
        assertThat(restored.getSizeBytes()).isEqualTo(first.getSizeBytes());
        assertThat(Path.of(restored.getStoredPath())).hasContent("old-save");
        assertThat(Path.of(first.getStoredPath())).as("the source version stays").hasContent("old-save");
    }

    @Test
    void restoreOfAnUnknownVersionFails() {
        assertThatThrownBy(() -> service.restore(user, game, 7, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("7");
        verify(repository, never()).save(any());
    }

    @Test
    void openStreamsAStoredSnapshotAndIsEmptyWhenTheObjectIsGone() throws Exception {
        GameSaveEntity saved = store("payload", null, false);

        Optional<InputStream> opened = service.open(saved);
        assertThat(opened).isPresent();
        try (InputStream in = opened.get()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("payload");
        }

        Files.delete(Path.of(saved.getStoredPath()));
        assertThat(service.open(saved)).isEmpty();
    }

    @Test
    void deleteRemovesTheObjectAndTheRow() throws Exception {
        GameSaveEntity saved = store("payload", null, false);

        service.delete(saved);

        assertThat(Path.of(saved.getStoredPath())).doesNotExist();
        verify(repository).delete(saved);
        assertThat(rows).isEmpty();
    }

    @Test
    void deleteToleratesAMissingObject() throws Exception {
        GameSaveEntity saved = store("payload", null, false);
        Files.delete(Path.of(saved.getStoredPath()));

        service.delete(saved);

        verify(repository).delete(saved);
    }

    @Test
    void deleteAllForGameRemovesEverySnapshot() throws Exception {
        GameSaveEntity first = store("v1", null, false);
        GameSaveEntity second = store("v2", null, false);

        service.deleteAllForGame(game.getId());

        assertThat(rows).isEmpty();
        assertThat(Path.of(first.getStoredPath())).doesNotExist();
        assertThat(Path.of(second.getStoredPath())).doesNotExist();
    }

    @Test
    void versionLooksUpOneSnapshot() throws Exception {
        GameSaveEntity saved = store("v1", null, false);

        assertThat(service.version(user, game, 1)).contains(saved);
        assertThat(service.version(user, game, 2)).isEmpty();
    }
}
