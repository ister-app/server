package app.ister.api.controller;

import app.ister.core.entity.GameEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Schema wiring of the change-detection fields: {@code version} must be the manifest's value
 * ({@link GameVersion}), {@code updatedAt} the row's last change.
 */
@GraphQlTest(GameController.class)
class GameControllerGraphQlTest {

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
    private GameEntity game;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user-1", "n/a", "ROLE_user"));
        LibraryEntity library = LibraryEntity.builder().name("Games").build();
        library.setId(UUID.randomUUID());
        game = GameEntity.builder().libraryEntity(library).name("Chrono").platform(GamePlatform.SNES)
                .form(GameForm.ROM).sizeBytes(12).fileCount(2)
                .dateUpdated(Instant.parse("2026-10-18T10:00:00Z")).build();
        game.setId(gameId);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(true);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

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
}
