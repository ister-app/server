package app.ister.api.controller;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.GameSaveEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure-logic branches of {@link GameController} that the schema test does not reach. */
@ExtendWith(MockitoExtension.class)
class GameControllerTest {

    @InjectMocks
    private GameController subject;

    @Mock
    private GameRepository gameRepository;
    @Mock
    private GameSaveRepository gameSaveRepository;
    @Mock
    private GameAchievementRepository achievementRepository;
    @Mock
    private UserGameAchievementRepository userAchievementRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private WatchStatusRepository watchStatusRepository;
    @Mock
    private WatchStatusService watchStatusService;
    @Mock
    private ContinueWatchingService continueWatchingService;
    @Mock
    private UserService userService;
    @Mock
    private LibraryAccessService libraryAccessService;
    @Mock
    private CacheDirectoryResolver cacheDirectoryResolver;
    @Mock
    private FileAccess fileAccess;
    @Mock
    private Authentication authentication;

    private final UUID libraryId = UUID.randomUUID();
    private GameEntity game;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        LibraryEntity library = LibraryEntity.builder().name("Games").build();
        library.setId(libraryId);
        game = GameEntity.builder().libraryEntity(library).name("Chrono").platform(GamePlatform.SNES)
                .form(GameForm.ROM).build();
        game.setId(UUID.randomUUID());
        user = UserEntity.builder().externalId("user-1").build();
        user.setId(UUID.randomUUID());
    }

    // ---- games paging branches ---------------------------------------------------------------

    @Test
    void gamesOfOneLibraryWithoutPlatformUseTheLibraryQuery() {
        when(libraryAccessService.canAccess(libraryId, authentication)).thenReturn(true);
        Page<GameEntity> page = new PageImpl<>(List.of(game));
        when(gameRepository.findByLibraryEntityId(eq(libraryId), any(Pageable.class))).thenReturn(page);

        Page<GameEntity> result = subject.games(Optional.of(2), Optional.of(10), Optional.of(SortingEnum.NAME),
                Optional.of(SortingOrder.DESCENDING), Optional.of(libraryId), Optional.empty(), authentication);

        assertSame(page, result);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(gameRepository).findByLibraryEntityId(eq(libraryId), captor.capture());
        assertEquals(2, captor.getValue().getPageNumber());
        assertEquals(10, captor.getValue().getPageSize());
        assertTrue(captor.getValue().getSort().getOrderFor("name").isDescending());
    }

    @Test
    void gamesOfAHiddenLibraryAreEmptyWithoutAQuery() {
        when(libraryAccessService.canAccess(libraryId, authentication)).thenReturn(false);

        Page<GameEntity> result = subject.games(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(libraryId), Optional.of(GamePlatform.SNES), authentication);

        assertTrue(result.isEmpty());
        assertEquals(20, result.getSize());
        verifyNoInteractions(gameRepository);
    }

    @Test
    void gamesOfAUserWithoutPlatformAreScopedToTheAllowedLibraries() {
        Set<UUID> allowed = Set.of(libraryId);
        when(libraryAccessService.allowedLibraryIds(authentication)).thenReturn(Optional.of(allowed));
        Page<GameEntity> page = new PageImpl<>(List.of(game));
        when(gameRepository.findByLibraryEntityIdIn(eq(allowed), any(Pageable.class))).thenReturn(page);

        Page<GameEntity> result = subject.games(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), authentication);

        assertSame(page, result);
        verify(gameRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void gamesOfAnAdminFilteredByPlatformUseThePlatformQuery() {
        when(libraryAccessService.allowedLibraryIds(authentication)).thenReturn(Optional.empty());
        Page<GameEntity> page = new PageImpl<>(List.of(game));
        when(gameRepository.findByPlatform(eq(GamePlatform.SNES), any(Pageable.class))).thenReturn(page);

        Page<GameEntity> result = subject.games(Optional.of(-3), Optional.of(5000), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(GamePlatform.SNES), authentication);

        assertSame(page, result);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(gameRepository).findByPlatform(eq(GamePlatform.SNES), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(Paging.MAX_PAGE_SIZE, captor.getValue().getPageSize());
        assertTrue(captor.getValue().getSort().getOrderFor("name").isAscending());
    }

    // ---- recordGamePlayed --------------------------------------------------------------------

    @Test
    void recordGamePlayedNeverSubtractsANegativeDuration() {
        mockVisibleGame();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        WatchStatusEntity status = WatchStatusEntity.builder().userEntity(user).gameEntity(game).playTimeMs(5_000L).build();
        when(watchStatusService.getOrCreateForGame(user, game)).thenReturn(status);

        WatchStatusEntity result = subject.recordGamePlayed(game.getId(), -900, authentication);

        assertSame(status, result);
        assertEquals(5_000L, result.getPlayTimeMs());
        assertEquals(5_000L, result.getProgressInMilliseconds());
        assertTrue(result.getDateUpdated().isAfter(Instant.now().minusSeconds(60)));
        verify(watchStatusRepository).save(status);
        verify(continueWatchingService).onWatchStatusChanged(status);
    }

    @Test
    void recordGamePlayedOnAHiddenGameIsNotFound() {
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(libraryAccessService.canAccess(game.getLibraryEntity(), authentication)).thenReturn(false);

        UUID gameId = game.getId();
        assertThrows(NoSuchElementException.class, () -> subject.recordGamePlayed(gameId, 1000, authentication));
        verifyNoInteractions(watchStatusService, continueWatchingService);
    }

    // ---- updateGameSavePaths -----------------------------------------------------------------

    @Test
    void updateGameSavePathsOnAnUnknownGameIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(gameRepository.findById(unknown)).thenReturn(Optional.empty());

        List<String> paths = List.of("<home>/x");
        assertThrows(NoSuchElementException.class, () -> subject.updateGameSavePaths(unknown, paths));
        verify(gameRepository, never()).save(any());
    }

    // ---- restoreGameSave / deleteGameSave ----------------------------------------------------

    @Test
    void restoreGameSaveCopiesTheOldZipAsTheNewHead() throws IOException {
        mockVisibleGame();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        DirectoryEntity sourceDir = DirectoryEntity.builder().path("/cache").build();
        DirectoryEntity cacheDir = DirectoryEntity.builder().path("/cache").build();
        GameSaveEntity source = GameSaveEntity.builder().userEntity(user).gameEntity(game).version(2)
                .cacheDirectoryEntity(sourceDir).storedPath("/cache/saves/old/2.zip").sizeBytes(17).sha256("deadbeef").build();
        GameSaveEntity head = GameSaveEntity.builder().version(5).build();
        when(gameSaveRepository.findByUserEntityIdAndGameEntityIdAndVersion(user.getId(), game.getId(), 2)).thenReturn(Optional.of(source));
        when(gameSaveRepository.findFirstByUserEntityIdAndGameEntityIdOrderByVersionDesc(user.getId(), game.getId())).thenReturn(Optional.of(head));
        CacheStore store = mock(CacheStore.class);
        when(cacheDirectoryResolver.store()).thenReturn(store);
        when(store.directory()).thenReturn(cacheDir);
        InputStream body = new ByteArrayInputStream(new byte[17]);
        when(fileAccess.open(sourceDir, "/cache/saves/old/2.zip")).thenReturn(body);
        String expectedKey = "saves/" + user.getId() + "/" + game.getId() + "/6.zip";
        when(store.write(expectedKey, body, 17L, "application/zip")).thenReturn("/cache/" + expectedKey);
        when(gameSaveRepository.save(any(GameSaveEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameSaveEntity restored = subject.restoreGameSave(game.getId(), 2, authentication);

        assertEquals(6, restored.getVersion());
        assertSame(user, restored.getUserEntity());
        assertSame(game, restored.getGameEntity());
        assertSame(cacheDir, restored.getCacheDirectoryEntity());
        assertEquals("/cache/" + expectedKey, restored.getStoredPath());
        assertEquals(17, restored.getSizeBytes());
        assertEquals("deadbeef", restored.getSha256());
        assertEquals("restored from v2", restored.getNote());
    }

    @Test
    void restoreGameSaveWithoutPriorSavesStartsAtVersionOne() throws IOException {
        mockVisibleGame();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        DirectoryEntity dir = DirectoryEntity.builder().path("/cache").build();
        GameSaveEntity source = GameSaveEntity.builder().version(1).cacheDirectoryEntity(dir).storedPath("/cache/a.zip")
                .sizeBytes(1).sha256("x").build();
        when(gameSaveRepository.findByUserEntityIdAndGameEntityIdAndVersion(user.getId(), game.getId(), 1)).thenReturn(Optional.of(source));
        when(gameSaveRepository.findFirstByUserEntityIdAndGameEntityIdOrderByVersionDesc(user.getId(), game.getId())).thenReturn(Optional.empty());
        CacheStore store = mock(CacheStore.class);
        when(cacheDirectoryResolver.store()).thenReturn(store);
        when(store.directory()).thenReturn(dir);
        when(fileAccess.open(any(), anyString())).thenReturn(InputStream.nullInputStream());
        when(store.write(anyString(), any(InputStream.class), anyLong(), anyString())).thenReturn("/cache/new.zip");
        when(gameSaveRepository.save(any(GameSaveEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameSaveEntity restored = subject.restoreGameSave(game.getId(), 1, authentication);

        assertEquals(1, restored.getVersion());
        verify(store).write(eq("saves/" + user.getId() + "/" + game.getId() + "/1.zip"), any(InputStream.class), eq(1L), eq("application/zip"));
    }

    @Test
    void restoreGameSaveOfAMissingVersionIsNotFound() {
        mockVisibleGame();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(gameSaveRepository.findByUserEntityIdAndGameEntityIdAndVersion(user.getId(), game.getId(), 9)).thenReturn(Optional.empty());

        UUID gameId = game.getId();
        assertThrows(NoSuchElementException.class, () -> subject.restoreGameSave(gameId, 9, authentication));
        verifyNoInteractions(cacheDirectoryResolver, fileAccess);
    }

    @Test
    void deleteGameSaveRemovesTheObjectAndTheRow() throws IOException {
        mockVisibleGame();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        DirectoryEntity dir = DirectoryEntity.builder().path("/cache").build();
        GameSaveEntity save = GameSaveEntity.builder().version(3).cacheDirectoryEntity(dir).storedPath("/cache/3.zip").build();
        when(gameSaveRepository.findByUserEntityIdAndGameEntityIdAndVersion(user.getId(), game.getId(), 3)).thenReturn(Optional.of(save));
        when(fileAccess.delete(dir, "/cache/3.zip")).thenReturn(true);

        assertTrue(subject.deleteGameSave(game.getId(), 3, authentication));

        verify(gameSaveRepository).delete(save);
    }

    @Test
    void deleteGameSaveStillDropsTheRowWhenTheObjectCannotBeDeleted() throws IOException {
        mockVisibleGame();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        DirectoryEntity dir = DirectoryEntity.builder().path("/cache").build();
        GameSaveEntity save = GameSaveEntity.builder().version(3).cacheDirectoryEntity(dir).storedPath("/cache/3.zip").build();
        when(gameSaveRepository.findByUserEntityIdAndGameEntityIdAndVersion(user.getId(), game.getId(), 3)).thenReturn(Optional.of(save));
        doThrow(new IOException("gone")).when(fileAccess).delete(dir, "/cache/3.zip");

        assertTrue(subject.deleteGameSave(game.getId(), 3, authentication));

        verify(gameSaveRepository).delete(save);
    }

    @Test
    void deleteGameSaveOfAMissingVersionIsFalse() {
        mockVisibleGame();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(gameSaveRepository.findByUserEntityIdAndGameEntityIdAndVersion(user.getId(), game.getId(), 4)).thenReturn(Optional.empty());

        assertFalse(subject.deleteGameSave(game.getId(), 4, authentication));

        verify(gameSaveRepository, never()).delete(any());
        verifyNoInteractions(fileAccess);
    }

    // ---- batch mappings ----------------------------------------------------------------------

    @Test
    void imagesAreGroupedPerGameInInputOrder() {
        GameEntity other = GameEntity.builder().name("Other").build();
        other.setId(UUID.randomUUID());
        ImageEntity cover = ImageEntity.builder().gameEntityId(other.getId()).path("/c.png").build();
        when(imageRepository.findByGameEntityIdIn(List.of(game.getId(), other.getId()))).thenReturn(List.of(cover));

        Map<GameEntity, List<ImageEntity>> result = subject.images(List.of(game, other));

        assertEquals(List.of(game, other), List.copyOf(result.keySet()));
        assertEquals(List.of(), result.get(game));
        assertEquals(List.of(cover), result.get(other));
    }

    @Test
    void lastPlayedAndPlayTimeKeepNullAndZeroForUnplayedGames() {
        GameEntity other = GameEntity.builder().name("Other").build();
        other.setId(UUID.randomUUID());
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        WatchStatusEntity status = WatchStatusEntity.builder().gameEntity(game).playTimeMs(1234L)
                .dateUpdated(Instant.parse("2026-10-18T12:00:00Z")).build();
        when(watchStatusRepository.findByUserEntityAndGameEntityIn(eq(user), anyCollection())).thenReturn(List.of(status));

        Map<GameEntity, String> lastPlayed = subject.lastPlayed(List.of(game, other), authentication);
        Map<GameEntity, Long> playTime = subject.playTimeMs(List.of(game, other), authentication);

        assertEquals("2026-10-18T12:00:00Z", lastPlayed.get(game));
        assertNull(lastPlayed.get(other));
        assertTrue(lastPlayed.containsKey(other));
        assertEquals(1234L, playTime.get(game));
        assertEquals(0L, playTime.get(other));
    }

    @Test
    void createdAtOfASaveIsTheIsoInstant() {
        GameSaveEntity save = GameSaveEntity.builder().dateCreated(Instant.parse("2026-10-18T12:00:00Z")).build();

        assertEquals("2026-10-18T12:00:00Z", subject.createdAt(save));
    }

    private void mockVisibleGame() {
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(libraryAccessService.canAccess(game.getLibraryEntity(), authentication)).thenReturn(true);
    }
}
