package app.ister.disk.games;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.GameSaveEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.GameAchievementSource;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.core.enums.StorageKind;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.UserService;
import app.ister.core.storage.FileAccess;
import app.ister.core.storage.LocalCopy;
import app.ister.core.storage.ObjectStoreRegistry;
import app.ister.disk.storage.FakeObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The save-cloud and achievement endpoints, the access checks, and the folder-game (PS3/Steam)
 * manifest and file paths that {@link GameResourceControllerTest} (single-file ROM) does not cover.
 */
class GameResourceControllerSavesTest {

    private static final Instant CREATED = Instant.parse("2026-10-10T08:00:00Z");

    @TempDir
    Path tempDir;

    private final GameRepository gameRepository = mock(GameRepository.class);
    private final MediaFileRepository mediaFileRepository = mock(MediaFileRepository.class);
    private final GameSaveService saveService = mock(GameSaveService.class);
    private final GameAchievementService achievementService = mock(GameAchievementService.class);
    private final LibraryAccessService access = mock(LibraryAccessService.class);
    private final UserService userService = mock(UserService.class);
    private final ObjectStoreRegistry registry = mock(ObjectStoreRegistry.class);
    private final FileAccess fileAccess = new FileAccess(registry);
    private final Authentication auth = new UsernamePasswordAuthenticationToken("alice", "n/a");
    private final UUID gameId = UUID.randomUUID();
    private final UserEntity user = UserEntity.builder().name("alice").build();
    private GameEntity game;
    private LibraryEntity library;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        library = LibraryEntity.builder().name("Games").build();
        library.setId(UUID.randomUUID());
        when(access.canAccess(library, auth)).thenReturn(true);
        when(userService.getOrCreateUser(auth)).thenReturn(user);
        game = GameEntity.builder().libraryEntity(library).platform(GamePlatform.PS3).form(GameForm.PS3_FOLDER)
                .name("Game").sizeBytes(0).fileCount(0).dateUpdated(CREATED).build();
        game.setId(gameId);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        mockMvc = MockMvcBuilders.standaloneSetup(new GameResourceController(gameRepository, mediaFileRepository,
                new GameTreeLister(registry), saveService, achievementService, access, userService,
                mock(LocalCopy.class), fileAccess)).build();
    }

    private GameSaveEntity save(int version, String sha, String device) {
        GameSaveEntity save = GameSaveEntity.builder().userEntity(user).gameEntity(game).version(version)
                .sizeBytes(5).sha256(sha).deviceName(device).note("note " + version).build();
        save.setId(UUID.randomUUID());
        save.setDateCreated(CREATED.plusSeconds(version));
        return save;
    }

    private Path localTree() throws IOException {
        Path root = tempDir.resolve("Game (2010)");
        Files.createDirectories(root.resolve("PS3_GAME/USRDIR"));
        Files.writeString(root.resolve("PS3_GAME/PARAM.SFO"), "sfo");
        Files.writeString(root.resolve("PS3_GAME/USRDIR/EBOOT.BIN"), "eboot-bytes");
        DirectoryEntity directory = DirectoryEntity.builder().name("games").path(tempDir.toString())
                .directoryType(DirectoryType.LIBRARY).build();
        game.setDirectoryEntity(directory);
        game.setRootPath(root.toString());
        game.setEntryPath("PS3_GAME/USRDIR/EBOOT.BIN");
        return root;
    }

    // ---- access ------------------------------------------------------------------------------

    @Test
    void anUnknownGameIs404() {
        UUID unknown = UUID.randomUUID();
        when(gameRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mockMvc.perform(get("/game/" + unknown + "/saves").principal(auth)))
                .hasCauseInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void aGameInAnInvisibleLibraryIsTreatedAsNotFound() {
        when(access.canAccess(library, auth)).thenReturn(false);

        assertThatThrownBy(() -> mockMvc.perform(get("/game/" + gameId + "/manifest").principal(auth)))
                .hasCauseInstanceOf(NoSuchElementException.class);
        verify(userService, never()).getOrCreateUser(any());
    }

    // ---- saves -------------------------------------------------------------------------------

    @Test
    void listsTheCallersSavesNewestFirst() throws Exception {
        when(saveService.list(user, game)).thenReturn(List.of(save(2, "bb", "laptop"), save(1, "aa", "desk")));

        mockMvc.perform(get("/game/" + gameId + "/saves").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].sha256").value("bb"))
                .andExpect(jsonPath("$[0].deviceName").value("laptop"))
                .andExpect(jsonPath("$[0].note").value("note 2"))
                .andExpect(jsonPath("$[0].createdAt").value(CREATED.plusSeconds(2).toString()))
                .andExpect(jsonPath("$[1].version").value(1));
    }

    @Test
    void uploadStoresTheBodyAndAnswers201() throws Exception {
        GameSaveEntity stored = save(3, "cc", "desk");
        ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<GameSaveService.Upload> upload = ArgumentCaptor.forClass(GameSaveService.Upload.class);
        when(saveService.store(eq(user), eq(game), upload.capture(), body.capture(), eq(5L))).thenAnswer(inv -> {
            assertThat(new String(((InputStream) inv.getArgument(3)).readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("zip!!");
            return stored;
        });

        mockMvc.perform(post("/game/" + gameId + "/saves").principal(auth)
                        .param("baseVersion", "2").param("force", "true").param("device", "desk").param("note", "after boss")
                        .header("X-Content-SHA256", "cc")
                        .contentType("application/zip").content("zip!!".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.sha256").value("cc"));

        assertThat(upload.getValue()).isEqualTo(new GameSaveService.Upload(2, true, "desk", "after boss", "cc"));
    }

    @Test
    void uploadWithANewerHeadAnswers409WithTheHeadsDetails() throws Exception {
        GameSaveEntity head = save(4, "dd", "laptop");
        when(saveService.store(any(), any(), any(), any(), anyLong())).thenThrow(new GameSaveService.ConflictException(head));

        mockMvc.perform(post("/game/" + gameId + "/saves").principal(auth).param("baseVersion", "2")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[]{1, 2, 3}))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("A newer save (version 4) exists on the server"))
                .andExpect(jsonPath("$.latestVersion").value(4))
                .andExpect(jsonPath("$.latestDevice").value("laptop"))
                .andExpect(jsonPath("$.latestCreatedAt").value(CREATED.plusSeconds(4).toString()));
    }

    @Test
    void uploadWithoutAContentLengthAnswers411() throws Exception {
        mockMvc.perform(post("/game/" + gameId + "/saves").principal(auth).contentType("application/zip"))
                .andExpect(status().isLengthRequired());

        verify(saveService, never()).store(any(), any(), any(), any(), anyLong());
    }

    @Test
    void downloadOfAnUnknownVersionIs404() throws Exception {
        when(saveService.version(user, game, 9)).thenReturn(Optional.empty());

        mockMvc.perform(get("/game/" + gameId + "/saves/9").principal(auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadStreamsTheImmutableZip() throws Exception {
        GameSaveEntity entity = save(1, "aa", "desk");
        when(saveService.version(user, game, 1)).thenReturn(Optional.of(entity));
        when(saveService.open(entity)).thenReturn(Optional.of(new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8))));

        MvcResult result = mockMvc.perform(get("/game/" + gameId + "/saves/1").principal(auth))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"aa\""))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 5))
                .andExpect(content().contentType("application/zip"))
                .andReturn();
        result.getAsyncResult();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("12345"));
    }

    @Test
    void downloadServesARange() throws Exception {
        GameSaveEntity entity = save(1, "aa", "desk");
        when(saveService.version(user, game, 1)).thenReturn(Optional.of(entity));
        when(saveService.open(entity)).thenReturn(Optional.of(new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8))));

        MvcResult result = mockMvc.perform(get("/game/" + gameId + "/saves/1").principal(auth)
                        .header(HttpHeaders.RANGE, "bytes=1-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 1-3/5"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 3))
                .andReturn();
        result.getAsyncResult();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isPartialContent())
                .andExpect(content().string("234"));
    }

    @Test
    void downloadOfAZipOnAnotherNodeFailsWhileStreaming() throws Exception {
        GameSaveEntity entity = save(1, "aa", "desk");
        when(saveService.version(user, game, 1)).thenReturn(Optional.of(entity));
        when(saveService.open(entity)).thenReturn(Optional.empty());

        MvcResult result = mockMvc.perform(get("/game/" + gameId + "/saves/1").principal(auth))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getAsyncResult()).isInstanceOf(NoSuchElementException.class)
                .hasToString("java.util.NoSuchElementException: Save " + entity.getId() + " is not on this node");
    }

    @Test
    void restoreAnswersTheNewHead() throws Exception {
        GameSaveEntity restored = save(5, "aa", "laptop");
        restored.setNote("restored from v1");
        when(saveService.restore(user, game, 1, "laptop")).thenReturn(restored);

        mockMvc.perform(post("/game/" + gameId + "/saves/1/restore").principal(auth).param("device", "laptop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.note").value("restored from v1"));
    }

    @Test
    void deleteRemovesAVersionOr404s() throws Exception {
        GameSaveEntity entity = save(1, "aa", "desk");
        when(saveService.version(user, game, 1)).thenReturn(Optional.of(entity));
        when(saveService.version(user, game, 2)).thenReturn(Optional.empty());

        mockMvc.perform(post("/game/" + gameId + "/saves/1/delete").principal(auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/game/" + gameId + "/saves/2/delete").principal(auth))
                .andExpect(status().isNotFound());

        verify(saveService).delete(entity);
    }

    // ---- achievements ------------------------------------------------------------------------

    @Test
    void reportAchievementsForwardsTheReportAndAnswersTheSummary() throws Exception {
        when(achievementService.report(eq(user), eq(game), eq(GameAchievementSource.PS3_TROPHY), any(), eq("desk")))
                .thenReturn(new GameAchievementService.Summary(42, 7));

        mockMvc.perform(post("/game/" + gameId + "/achievements").principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"PS3_TROPHY","deviceName":"desk","achievements":[
                                  {"externalId":"t1","name":"First","grade":"BRONZE","hidden":false,"sortOrder":1,
                                   "unlockedAt":"2026-10-01T10:00:00Z"},
                                  {"externalId":"t2","name":"Second","hidden":false,"sortOrder":2}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(42))
                .andExpect(jsonPath("$.unlocked").value(7));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GameAchievementService.Reported>> reported = ArgumentCaptor.forClass(List.class);
        verify(achievementService).report(eq(user), eq(game), eq(GameAchievementSource.PS3_TROPHY), reported.capture(), eq("desk"));
        assertThat(reported.getValue()).containsExactly(
                new GameAchievementService.Reported("t1", "First", null, "BRONZE", false, 1, Instant.parse("2026-10-01T10:00:00Z")),
                new GameAchievementService.Reported("t2", "Second", null, null, false, 2, null));
    }

    @Test
    void reportWithoutASourceIsRejected() {
        assertThatThrownBy(() -> mockMvc.perform(post("/game/" + gameId + "/achievements").principal(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"achievements\":[]}")))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source and achievements are required");
    }

    @Test
    void achievementIconAnswers204WhenStoredAnd404ForAnUnknownDefinition() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        when(achievementService.storeIcon(game, GameAchievementSource.STEAM, "ACH_1", png))
                .thenReturn(Optional.of(mock(ImageEntity.class)));
        when(achievementService.storeIcon(game, GameAchievementSource.STEAM, "MISSING", png)).thenReturn(Optional.empty());

        mockMvc.perform(post("/game/" + gameId + "/achievements/STEAM/ACH_1/icon").principal(auth)
                        .contentType("image/png").content(png))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/game/" + gameId + "/achievements/STEAM/MISSING/icon").principal(auth)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(png))
                .andExpect(status().isNotFound());
    }

    // ---- folder games ------------------------------------------------------------------------

    @Test
    void aFolderGameManifestIsListedLiveFromItsFolder() throws Exception {
        localTree();

        mockMvc.perform(get("/game/" + gameId + "/manifest").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form").value("PS3_FOLDER"))
                .andExpect(jsonPath("$.fileCount").value(2))
                .andExpect(jsonPath("$.totalBytes").value(14))
                .andExpect(jsonPath("$.files[0].path").value("PS3_GAME/PARAM.SFO"))
                .andExpect(jsonPath("$.files[0].entry").value(false))
                .andExpect(jsonPath("$.files[1].path").value("PS3_GAME/USRDIR/EBOOT.BIN"))
                .andExpect(jsonPath("$.files[1].entry").value(true));
        verify(mediaFileRepository, never()).findByGameEntityId(any());
    }

    @Test
    void aSteamManifestPrefixesTheInstallDirAndAddsTheAcf() throws Exception {
        Path root = tempDir.resolve("steam");
        Files.createDirectories(root.resolve("common/MyGame/bin"));
        Files.writeString(root.resolve("common/MyGame/bin/game.exe"), "exe");
        Files.writeString(root.resolve("appmanifest_440.acf"), "acf-contents");
        game.setForm(GameForm.STEAM);
        game.setPlatform(GamePlatform.PC);
        game.setDirectoryEntity(DirectoryEntity.builder().name("games").path(tempDir.toString())
                .directoryType(DirectoryType.LIBRARY).build());
        game.setRootPath(root.toString());
        game.setSteamAppId(440L);
        game.setSteamInstallDir("common/MyGame");
        game.setEntryPath("common/MyGame/bin/game.exe");

        mockMvc.perform(get("/game/" + gameId + "/manifest").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steamAppId").value(440))
                .andExpect(jsonPath("$.steamInstallDir").value("common/MyGame"))
                .andExpect(jsonPath("$.fileCount").value(2))
                .andExpect(jsonPath("$.totalBytes").value(15))
                .andExpect(jsonPath("$.files[0].path").value("common/MyGame/bin/game.exe"))
                .andExpect(jsonPath("$.files[0].entry").value(true))
                .andExpect(jsonPath("$.files[1].path").value("appmanifest_440.acf"))
                .andExpect(jsonPath("$.files[1].size").value(12));
    }

    @Test
    void aFolderGameFileIsServedFromItsFolder() throws Exception {
        localTree();

        MvcResult result = mockMvc.perform(get("/game/" + gameId + "/file/PS3_GAME/USRDIR/EBOOT.BIN").principal(auth))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 11))
                .andReturn();
        result.getAsyncResult();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("eboot-bytes"));
    }

    @Test
    void aMissingFolderGameFileIs404() throws Exception {
        localTree();

        mockMvc.perform(get("/game/" + gameId + "/file/PS3_GAME/nope.bin").principal(auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSymlinkLeavingTheGameFolderIsRefused() throws Exception {
        Path root = localTree();
        Path outside = tempDir.resolve("secret.txt");
        Files.writeString(outside, "secret");
        Files.createSymbolicLink(root.resolve("PS3_GAME/link.txt"), outside);

        mockMvc.perform(get("/game/" + gameId + "/file/PS3_GAME/link.txt").principal(auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPathEscapingTheGameFolderIsRejected() {
        assertThatThrownBy(() -> mockMvc.perform(get("/game/" + gameId + "/file/../other").principal(auth)))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anS3FolderGameFileIsStattedAndOpenedThroughTheObjectStore() throws Exception {
        DirectoryEntity directory = DirectoryEntity.builder().name("games-s3").path("s3://bucket/games")
                .storageKind(StorageKind.S3).directoryType(DirectoryType.LIBRARY).build();
        FakeObjectStore store = new FakeObjectStore("bucket")
                .put("games/ps3/Game/PS3_GAME/USRDIR/EBOOT.BIN", "s3-eboot");
        when(registry.forDirectory(directory)).thenReturn(store);
        game.setDirectoryEntity(directory);
        game.setRootPath("s3://bucket/games/ps3/Game");

        MvcResult result = mockMvc.perform(get("/game/" + gameId + "/file/PS3_GAME/USRDIR/EBOOT.BIN").principal(auth))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 8))
                .andReturn();
        result.getAsyncResult();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(content().string("s3-eboot"));

        mockMvc.perform(get("/game/" + gameId + "/file/PS3_GAME/missing.bin").principal(auth))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/game/" + gameId + "/manifest").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileCount").value(1))
                .andExpect(jsonPath("$.files[0].path").value("PS3_GAME/USRDIR/EBOOT.BIN"));
    }

    @Test
    void theFolderListingIsCachedPerSizeAndCount() throws Exception {
        Path root = localTree();

        mockMvc.perform(get("/game/" + gameId + "/manifest").principal(auth))
                .andExpect(jsonPath("$.fileCount").value(2));
        Files.writeString(root.resolve("PS3_GAME/extra.bin"), "x");
        mockMvc.perform(get("/game/" + gameId + "/manifest").principal(auth))
                .andExpect(jsonPath("$.fileCount").value(2)) // same size/count key: cached listing
                .andExpect(status().isOk());

        game.setFileCount(3);
        game.setSizeBytes(15);
        mockMvc.perform(get("/game/" + gameId + "/manifest").principal(auth))
                .andExpect(jsonPath("$.fileCount").value(3));
    }
}
