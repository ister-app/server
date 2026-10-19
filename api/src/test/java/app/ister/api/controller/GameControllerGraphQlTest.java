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
import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.core.enums.ImageType;
import app.ister.core.games.GameSavePaths;
import app.ister.core.games.GameSavePaths.SavePath;
import app.ister.core.games.GameVersion;
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
import app.ister.core.storage.FileAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Schema wiring of the games API: browsing, the per-user play/save/achievement state and the
 * change-detection fields ({@code version} must be the manifest's value, see {@link GameVersion}).
 */
@GraphQlTest(GameController.class)
class GameControllerGraphQlTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private GameRepository gameRepository;
    @MockitoBean
    private GameSaveRepository gameSaveRepository;
    @MockitoBean
    private GameAchievementRepository achievementRepository;
    @MockitoBean
    private UserGameAchievementRepository userAchievementRepository;
    @MockitoBean
    private ImageRepository imageRepository;
    @MockitoBean
    private MetadataRepository metadataRepository;
    @MockitoBean
    private WatchStatusRepository watchStatusRepository;
    @MockitoBean
    private WatchStatusService watchStatusService;
    @MockitoBean
    private ContinueWatchingService continueWatchingService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private LibraryAccessService libraryAccessService;
    @MockitoBean
    private CacheDirectoryResolver cacheDirectoryResolver;
    @MockitoBean
    private FileAccess fileAccess;

    private final UUID gameId = UUID.randomUUID();
    private final UUID libraryId = UUID.randomUUID();
    private GameEntity game;
    private UserEntity user;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user-1", "n/a", "ROLE_user"));
        LibraryEntity library = LibraryEntity.builder().name("Games").build();
        library.setId(libraryId);
        game = GameEntity.builder().libraryEntity(library).name("Chrono").platform(GamePlatform.SNES)
                .form(GameForm.ROM).sizeBytes(12).fileCount(2)
                .dateUpdated(Instant.parse("2026-10-18T10:00:00Z")).build();
        game.setId(gameId);
        user = UserEntity.builder().name("test-user").externalId("user-1").build();
        user.setId(UUID.randomUUID());
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(true);
        when(libraryAccessService.canAccess(any(UUID.class), any())).thenReturn(true);
        when(userService.getOrCreateUser(any())).thenReturn(user);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ---- gameById / change detection -----------------------------------------------------------

    @Test
    void versionAndUpdatedAtComeOutOfTheSchema() {
        GraphQlTester.Response response = graphQlTester.document("""
                        query($id: ID!) {
                          gameById(id: $id) { version steamBuildId updatedAt }
                        }""")
                .variable("id", gameId)
                .execute();

        assertEquals(GameVersion.of(game), response.path("gameById.version").entity(String.class).get());
        assertEquals("2026-10-18T10:00:00Z", response.path("gameById.updatedAt").entity(String.class).get());
        response.path("gameById.steamBuildId").valueIsNull();
    }

    @Test
    void aSteamGameReportsItsBuildIdAsVersion() {
        game.setSteamBuildId(987654L);

        GraphQlTester.Response response = graphQlTester.document("""
                        query($id: ID!) {
                          gameById(id: $id) { version steamBuildId }
                        }""")
                .variable("id", gameId)
                .execute();

        assertEquals("987654", response.path("gameById.version").entity(String.class).get());
        assertEquals("987654", response.path("gameById.steamBuildId").entity(String.class).get());
    }

    @Test
    void gameByIdIsNullWhenTheLibraryIsNotVisible() {
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(false);

        graphQlTester.document("""
                        query($id: ID!) { gameById(id: $id) { id } }""")
                .variable("id", gameId)
                .execute()
                .path("gameById").valueIsNull();
    }

    // ---- Game scalar fields ------------------------------------------------------------------

    @Test
    void scalarFieldsFallBackAndFormatAsTheSchemaPromises() {
        game.setSteamAppId(620L);
        game.setDateUpdated(null);

        Map<String, Object> result = graphQlTester.document("""
                        query($id: ID!) {
                          gameById(id: $id) {
                            title steamAppId updatedAt
                            launcher { kind retroArchCore }
                            library { id name }
                          }
                        }""")
                .variable("id", gameId)
                .execute()
                .path("gameById").entity(MAP).get();

        assertEquals("Chrono", result.get("title"));
        assertEquals("620", result.get("steamAppId"));
        assertNull(result.get("updatedAt"));
        assertEquals(Map.of("kind", "RETROARCH", "retroArchCore", "snes9x"), result.get("launcher"));
        assertEquals(Map.of("id", libraryId.toString(), "name", "Games"), result.get("library"));
    }

    @Test
    void titleFromParamSfoWinsOverThePathName() {
        game.setTitle("Chrono Trigger");

        graphQlTester.document("""
                        query($id: ID!) { gameById(id: $id) { title steamAppId } }""")
                .variable("id", gameId)
                .execute()
                .path("gameById.title").entity(String.class).isEqualTo("Chrono Trigger")
                .path("gameById.steamAppId").valueIsNull();
    }

    @Test
    void savePathsComeFromTheManifestWhenThereIsNoOverride() {
        game.setManifestSavePaths(GameSavePaths.toJson(List.of(new SavePath("<winAppData>/Chrono", "windows"))));

        Map<String, Object> result = graphQlTester.document("""
                        query($id: ID!) {
                          gameById(id: $id) { savePathSource savePaths { path os } savePathOverride { path } }
                        }""")
                .variable("id", gameId)
                .execute()
                .path("gameById").entity(MAP).get();

        assertEquals("MANIFEST", result.get("savePathSource"));
        assertEquals(List.of(Map.of("path", "<winAppData>/Chrono", "os", "windows")), result.get("savePaths"));
        assertEquals(List.of(), result.get("savePathOverride"));
    }

    @Test
    void adminOverrideWinsOverTheManifestSavePaths() {
        game.setManifestSavePaths(GameSavePaths.toJson(List.of(new SavePath("<winAppData>/Chrono", "windows"))));
        game.setSavePaths(GameSavePaths.toJson(List.of(new SavePath("<home>/.chrono", null))));

        Map<String, Object> result = graphQlTester.document("""
                        query($id: ID!) {
                          gameById(id: $id) { savePathSource savePaths { path os } savePathOverride { path os } }
                        }""")
                .variable("id", gameId)
                .execute()
                .path("gameById").entity(MAP).get();

        Map<String, Object> expected = new java.util.HashMap<>();
        expected.put("path", "<home>/.chrono");
        expected.put("os", null);
        assertEquals("ADMIN", result.get("savePathSource"));
        assertEquals(List.of(expected), result.get("savePaths"));
        assertEquals(List.of(expected), result.get("savePathOverride"));
    }

    @Test
    void savePathSourceIsNoneWithoutAnyPaths() {
        Map<String, Object> result = graphQlTester.document("""
                        query($id: ID!) { gameById(id: $id) { savePathSource savePaths { path } } }""")
                .variable("id", gameId)
                .execute()
                .path("gameById").entity(MAP).get();

        assertEquals("NONE", result.get("savePathSource"));
        assertEquals(List.of(), result.get("savePaths"));
    }

    // ---- Game relation fields ----------------------------------------------------------------

    @Test
    void imagesAndMetadataAreLoadedThroughTheRepositories() {
        ImageEntity cover = ImageEntity.builder().path("/cache/cover.png").type(ImageType.COVER).gameEntityId(gameId).build();
        cover.setId(UUID.randomUUID());
        MetadataEntity metadata = MetadataEntity.builder().title("Chrono Trigger").language("eng").build();
        metadata.setId(UUID.randomUUID());
        when(imageRepository.findByGameEntityIdIn(List.of(gameId))).thenReturn(List.of(cover));
        when(metadataRepository.findByGameEntityId(gameId)).thenReturn(List.of(metadata));

        Map<String, Object> result = graphQlTester.document("""
                        query($id: ID!) {
                          gameById(id: $id) { images { id path } metadata { title language } }
                        }""")
                .variable("id", gameId)
                .execute()
                .path("gameById").entity(MAP).get();

        assertEquals(List.of(Map.of("id", cover.getId().toString(), "path", "/cache/cover.png")), result.get("images"));
        assertEquals(List.of(Map.of("title", "Chrono Trigger", "language", "eng")), result.get("metadata"));
    }

    @Test
    void imagesAreEmptyForAGameWithoutArtwork() {
        when(imageRepository.findByGameEntityIdIn(List.of(gameId))).thenReturn(List.of());

        graphQlTester.document("""
                        query($id: ID!) { gameById(id: $id) { images { id } } }""")
                .variable("id", gameId)
                .execute()
                .path("gameById.images").entityList(Object.class).hasSize(0);
    }

    @Test
    void lastPlayedAndPlayTimeComeFromTheUsersWatchStatus() {
        WatchStatusEntity status = WatchStatusEntity.builder().userEntity(user).gameEntity(game).playTimeMs(90_000L)
                .dateUpdated(Instant.parse("2026-10-17T20:00:00Z")).build();
        when(watchStatusRepository.findByUserEntityAndGameEntityIn(eq(user), anyCollection())).thenReturn(List.of(status));

        Map<String, Object> result = graphQlTester.document("""
                        query($id: ID!) { gameById(id: $id) { lastPlayed playTimeMs } }""")
                .variable("id", gameId)
                .execute()
                .path("gameById").entity(MAP).get();

        assertEquals("2026-10-17T20:00:00Z", result.get("lastPlayed"));
        assertEquals(90_000.0, ((Number) result.get("playTimeMs")).doubleValue());
    }

    @Test
    void anUnplayedGameHasNoLastPlayedAndZeroPlayTime() {
        when(watchStatusRepository.findByUserEntityAndGameEntityIn(eq(user), anyCollection())).thenReturn(List.of());

        Map<String, Object> result = graphQlTester.document("""
                        query($id: ID!) { gameById(id: $id) { lastPlayed playTimeMs } }""")
                .variable("id", gameId)
                .execute()
                .path("gameById").entity(MAP).get();

        assertNull(result.get("lastPlayed"));
        assertEquals(0.0, ((Number) result.get("playTimeMs")).doubleValue());
    }

    @Test
    void savesListTheUsersSnapshotsNewestFirst() {
        GameSaveEntity save = GameSaveEntity.builder().userEntity(user).gameEntity(game).version(3).sizeBytes(1024)
                .sha256("abc").deviceName("deck").note("before boss")
                .dateCreated(Instant.parse("2026-10-16T08:00:00Z")).build();
        save.setId(UUID.randomUUID());
        when(gameSaveRepository.findByUserEntityIdAndGameEntityIdOrderByVersionDesc(user.getId(), gameId))
                .thenReturn(List.of(save));

        List<Map<String, Object>> saves = graphQlTester.document("""
                        query($id: ID!) {
                          gameById(id: $id) { saves { id version sizeBytes sha256 deviceName note createdAt } }
                        }""")
                .variable("id", gameId)
                .execute()
                .path("gameById.saves").entityList(MAP).get();

        assertEquals(1, saves.size());
        assertEquals(save.getId().toString(), saves.getFirst().get("id"));
        assertEquals(3, saves.getFirst().get("version"));
        assertEquals(1024.0, ((Number) saves.getFirst().get("sizeBytes")).doubleValue());
        assertEquals("abc", saves.getFirst().get("sha256"));
        assertEquals("deck", saves.getFirst().get("deviceName"));
        assertEquals("before boss", saves.getFirst().get("note"));
        assertEquals("2026-10-16T08:00:00Z", saves.getFirst().get("createdAt"));
    }

    @Test
    void achievementsSummarizeDefinitionsAgainstTheUsersUnlocks() {
        GameAchievementEntity unlocked = achievement("TROPHY_1", "First Steps", "BRONZE");
        GameAchievementEntity locked = achievement("TROPHY_2", "Secret", null);
        locked.setHidden(true);
        Instant unlockedAt = Instant.parse("2026-10-15T18:30:00Z");
        when(achievementRepository.findByGameEntityIdOrderBySortOrderAscNameAsc(gameId)).thenReturn(List.of(unlocked, locked));
        when(userAchievementRepository.findByUserAndGame(user.getId(), gameId)).thenReturn(List.of(
                UserGameAchievementEntity.builder().userEntity(user).gameAchievementEntity(unlocked).unlockedAt(unlockedAt).build()));

        Map<String, Object> summary = graphQlTester.document("""
                        query($id: ID!) {
                          gameById(id: $id) {
                            achievements {
                              total unlocked
                              list { id source externalId name description grade hidden icon { id } unlockedAt game { id } }
                            }
                          }
                        }""")
                .variable("id", gameId)
                .execute()
                .path("gameById.achievements").entity(MAP).get();

        assertEquals(2, summary.get("total"));
        assertEquals(1, summary.get("unlocked"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) summary.get("list");
        assertEquals(2, list.size());
        Map<String, Object> first = list.get(0);
        assertEquals(unlocked.getId().toString(), first.get("id"));
        assertEquals("PS3_TROPHY", first.get("source"));
        assertEquals("TROPHY_1", first.get("externalId"));
        assertEquals("First Steps", first.get("name"));
        assertEquals("BRONZE", first.get("grade"));
        assertEquals(false, first.get("hidden"));
        assertNull(first.get("icon"));
        assertEquals("2026-10-15T18:30:00Z", first.get("unlockedAt"));
        assertEquals(Map.of("id", gameId.toString()), first.get("game"));
        Map<String, Object> second = list.get(1);
        assertEquals(true, second.get("hidden"));
        assertNull(second.get("grade"));
        assertNull(second.get("unlockedAt"));
    }

    // ---- games -------------------------------------------------------------------------------

    @Test
    void gamesOfOneLibraryAndPlatformPageThroughTheRepository() {
        Pageable pageable = PageRequest.of(1, 5);
        when(gameRepository.findByLibraryEntityIdAndPlatform(eq(libraryId), eq(GamePlatform.SNES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(game), pageable, 6));

        Map<String, Object> page = graphQlTester.document("""
                        query($libraryId: ID!) {
                          games(page: 1, size: 5, libraryId: $libraryId, platform: SNES, sorting: NAME, sortingOrder: DESCENDING) {
                            content { id name platform form } totalPages totalElements number size
                          }
                        }""")
                .variable("libraryId", libraryId)
                .execute()
                .path("games").entity(MAP).get();

        assertEquals(List.of(Map.of("id", gameId.toString(), "name", "Chrono", "platform", "SNES", "form", "ROM")),
                page.get("content"));
        assertEquals(2, page.get("totalPages"));
        assertEquals(6, page.get("totalElements"));
        assertEquals(1, page.get("number"));
        assertEquals(5, page.get("size"));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(gameRepository).findByLibraryEntityIdAndPlatform(eq(libraryId), eq(GamePlatform.SNES), captor.capture());
        assertEquals(1, captor.getValue().getPageNumber());
        assertEquals(5, captor.getValue().getPageSize());
        assertTrue(captor.getValue().getSort().getOrderFor("name").isDescending());
    }

    @Test
    void gamesOfAHiddenLibraryAreAnEmptyPage() {
        when(libraryAccessService.canAccess(eq(libraryId), any())).thenReturn(false);

        Map<String, Object> page = graphQlTester.document("""
                        query($libraryId: ID!) {
                          games(libraryId: $libraryId) { content { id } totalElements number size }
                        }""")
                .variable("libraryId", libraryId)
                .execute()
                .path("games").entity(MAP).get();

        assertEquals(List.of(), page.get("content"));
        assertEquals(0, page.get("totalElements"));
        assertEquals(20, page.get("size"));
        verify(gameRepository, never()).findByLibraryEntityId(any(), any());
    }

    @Test
    void gamesWithoutFiltersListEverythingForAnAdmin() {
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());
        when(gameRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(game)));

        graphQlTester.document("""
                        query { games { content { id } } }""")
                .execute()
                .path("games.content[0].id").entity(String.class).isEqualTo(gameId.toString());
    }

    @Test
    void gamesOfAUserAreScopedToTheAllowedLibraries() {
        Set<UUID> allowed = Set.of(libraryId);
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.of(allowed));
        when(gameRepository.findByLibraryEntityIdInAndPlatform(eq(allowed), eq(GamePlatform.PS3), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        graphQlTester.document("""
                        query { games(platform: PS3) { content { id } totalElements } }""")
                .execute()
                .path("games.totalElements").entity(Integer.class).isEqualTo(0);
    }

    // ---- gamePlatforms / recentAchievements ---------------------------------------------------

    @Test
    void gamePlatformsDescribeEveryPlatformWithItsLauncher() {
        List<Map<String, Object>> platforms = graphQlTester.document("""
                        query {
                          gamePlatforms { platform folderNames entryExtensions companionExtensions launcher { kind retroArchCore } }
                        }""")
                .execute()
                .path("gamePlatforms").entityList(MAP).get();

        assertEquals(GamePlatform.values().length, platforms.size());
        Map<String, Object> psx = platforms.stream().filter(p -> "PSX".equals(p.get("platform"))).findFirst().orElseThrow();
        assertEquals(List.of("psx", "ps1"), psx.get("folderNames"));
        assertEquals(List.of("cue", "chd", "pbp"), psx.get("entryExtensions"));
        assertEquals(List.of("bin", "sub", "img", "ccd"), psx.get("companionExtensions"));
        assertEquals(Map.of("kind", "RETROARCH", "retroArchCore", "swanstation"), psx.get("launcher"));
        Map<String, Object> steam = platforms.stream().filter(p -> "STEAM".equals(p.get("platform"))).findFirst().orElseThrow();
        Map<String, Object> steamLauncher = new java.util.HashMap<>();
        steamLauncher.put("kind", "STEAM");
        steamLauncher.put("retroArchCore", null);
        assertEquals(steamLauncher, steam.get("launcher"));
    }

    @Test
    void recentAchievementsSkipGamesInHiddenLibraries() {
        LibraryEntity hiddenLibrary = LibraryEntity.builder().name("Hidden").build();
        hiddenLibrary.setId(UUID.randomUUID());
        GameEntity hiddenGame = GameEntity.builder().libraryEntity(hiddenLibrary).name("Secret").platform(GamePlatform.PC)
                .form(GameForm.PC_PORTABLE_ZIP).build();
        hiddenGame.setId(UUID.randomUUID());
        GameAchievementEntity visible = achievement("ACH_1", "Visible", null);
        GameAchievementEntity hidden = achievement("ACH_2", "Hidden", null);
        hidden.setGameEntity(hiddenGame);
        when(libraryAccessService.canAccess(eq(hiddenLibrary), any())).thenReturn(false);
        when(userAchievementRepository.findRecentByUser(eq(user.getId()), any(Pageable.class))).thenReturn(List.of(
                UserGameAchievementEntity.builder().gameAchievementEntity(visible).unlockedAt(Instant.parse("2026-10-18T09:00:00Z")).build(),
                UserGameAchievementEntity.builder().gameAchievementEntity(hidden).unlockedAt(Instant.parse("2026-10-18T08:00:00Z")).build()));

        List<Map<String, Object>> result = graphQlTester.document("""
                        query { recentAchievements(limit: 500) { externalId unlockedAt game { id title } } }""")
                .execute()
                .path("recentAchievements").entityList(MAP).get();

        assertEquals(1, result.size());
        assertEquals("ACH_1", result.getFirst().get("externalId"));
        assertEquals("2026-10-18T09:00:00Z", result.getFirst().get("unlockedAt"));
        assertEquals(Map.of("id", gameId.toString(), "title", "Chrono"), result.getFirst().get("game"));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(userAchievementRepository).findRecentByUser(eq(user.getId()), captor.capture());
        assertEquals(100, captor.getValue().getPageSize());
    }

    @Test
    void recentAchievementsDefaultToTwentyEntries() {
        when(userAchievementRepository.findRecentByUser(eq(user.getId()), any(Pageable.class))).thenReturn(List.of());

        graphQlTester.document("""
                        query { recentAchievements { id } }""")
                .execute()
                .path("recentAchievements").entityList(Object.class).hasSize(0);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(userAchievementRepository).findRecentByUser(eq(user.getId()), captor.capture());
        assertEquals(20, captor.getValue().getPageSize());
    }

    // ---- mutations ---------------------------------------------------------------------------

    @Test
    void recordGamePlayedAddsToPlayTimeAndUpdatesContinueWatching() {
        WatchStatusEntity status = WatchStatusEntity.builder().userEntity(user).gameEntity(game).playQueueItemId(gameId)
                .playTimeMs(60_000L).build();
        status.setId(UUID.randomUUID());
        when(watchStatusService.getOrCreateForGame(user, game)).thenReturn(status);

        Map<String, Object> result = graphQlTester.document("""
                        mutation($id: ID!) {
                          recordGamePlayed(gameId: $id, durationMs: 30000) { id progressInMilliseconds watched }
                        }""")
                .variable("id", gameId)
                .execute()
                .path("recordGamePlayed").entity(MAP).get();

        assertEquals(status.getId().toString(), result.get("id"));
        assertEquals(90_000, result.get("progressInMilliseconds"));
        assertEquals(false, result.get("watched"));
        assertEquals(90_000L, status.getPlayTimeMs());
        verify(watchStatusRepository).save(status);
        verify(continueWatchingService).onWatchStatusChanged(status);
    }

    @Test
    void recordGamePlayedOnAnUnknownGameIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(gameRepository.findById(unknown)).thenReturn(Optional.empty());

        graphQlTester.document("""
                        mutation($id: ID!) { recordGamePlayed(gameId: $id, durationMs: 1000) { id } }""")
                .variable("id", unknown)
                .execute()
                .errors().satisfy(errors -> {
                    assertEquals(1, errors.size());
                    assertEquals(ErrorType.NOT_FOUND, errors.getFirst().getErrorType());
                });
        verify(watchStatusService, never()).getOrCreateForGame(any(), any());
    }

    @Test
    void recordGamePlayedOnAHiddenGameIsNotFound() {
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(false);

        graphQlTester.document("""
                        mutation($id: ID!) { recordGamePlayed(gameId: $id, durationMs: 1000) { id } }""")
                .variable("id", gameId)
                .execute()
                .errors().satisfy(errors -> assertEquals(ErrorType.NOT_FOUND, errors.getFirst().getErrorType()));
        verify(continueWatchingService, never()).onWatchStatusChanged(any());
    }

    @Test
    void updateGameSavePathsStoresTheStrippedOverride() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin-1", "n/a", "ROLE_admin"));
        when(gameRepository.save(game)).thenReturn(game);

        Map<String, Object> result = graphQlTester.document("""
                        mutation($id: ID!) {
                          updateGameSavePaths(gameId: $id, savePaths: ["  <winAppData>/Chrono ", "", "   ", "<home>/.chrono"]) {
                            savePathSource savePathOverride { path os }
                          }
                        }""")
                .variable("id", gameId)
                .execute()
                .path("updateGameSavePaths").entity(MAP).get();

        assertEquals("ADMIN", result.get("savePathSource"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> override = (List<Map<String, Object>>) result.get("savePathOverride");
        assertEquals(List.of("<winAppData>/Chrono", "<home>/.chrono"), override.stream().map(p -> p.get("path")).toList());
        assertEquals(List.of(new SavePath("<winAppData>/Chrono", null), new SavePath("<home>/.chrono", null)),
                GameSavePaths.fromJson(game.getSavePaths()));
        verify(gameRepository).save(game);
    }

    @Test
    void updateGameSavePathsWithOnlyBlanksClearsTheOverride() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin-1", "n/a", "ROLE_admin"));
        game.setSavePaths(GameSavePaths.toJson(List.of(new SavePath("<home>/.chrono", null))));
        when(gameRepository.save(game)).thenReturn(game);

        graphQlTester.document("""
                        mutation($id: ID!) {
                          updateGameSavePaths(gameId: $id, savePaths: [" ", ""]) { savePathSource }
                        }""")
                .variable("id", gameId)
                .execute()
                .path("updateGameSavePaths.savePathSource").entity(String.class).isEqualTo("NONE");

        assertNull(game.getSavePaths());
    }

    @Test
    void updateGameSavePathsOnAnUnknownGameIsNotFound() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin-1", "n/a", "ROLE_admin"));
        UUID unknown = UUID.randomUUID();
        when(gameRepository.findById(unknown)).thenReturn(Optional.empty());

        graphQlTester.document("""
                        mutation($id: ID!) { updateGameSavePaths(gameId: $id, savePaths: ["x"]) { id } }""")
                .variable("id", unknown)
                .execute()
                .errors().satisfy(errors -> assertEquals(ErrorType.NOT_FOUND, errors.getFirst().getErrorType()));
        verify(gameRepository, never()).save(any());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private GameAchievementEntity achievement(String externalId, String name, String grade) {
        GameAchievementEntity definition = GameAchievementEntity.builder().gameEntity(game).source(GameAchievementSource.PS3_TROPHY)
                .externalId(externalId).name(name).grade(grade).build();
        definition.setId(UUID.randomUUID());
        return definition;
    }
}
