package app.ister.worker.events.gamefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.GameFoundData;
import app.ister.core.games.GameSavePaths;
import app.ister.core.games.GameSavePaths.SavePath;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.service.ServerEventService;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import app.ister.core.storage.FileAccess;
import app.ister.worker.events.gamefound.LudusaviManifestService.Entry;
import app.ister.worker.events.gamefound.LudusaviManifestService.Match;
import app.ister.worker.events.gamefound.LudusaviManifestService.MatchedBy;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleGameFoundTest {

    private static final String ROOT = "/games/ps3/Demon's Souls (2009)";

    @InjectMocks
    private HandleGameFound subject;

    @Mock
    private GameRepository gameRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private FileAccess fileAccess;
    @Mock
    private CacheDirectoryResolver cacheDirectoryResolver;
    @Mock
    private ImageSave imageSave;
    @Mock
    private LudusaviManifestService ludusavi;
    @Mock
    private GameMetadataProvider metadataProvider;
    @Mock
    private ServerEventService serverEventService;
    @Mock
    private CacheStore cacheStore;

    private final DirectoryEntity libraryDir = DirectoryEntity.builder().id(UUID.randomUUID()).name("games-1").path("/games").build();
    private final DirectoryEntity cacheDir = DirectoryEntity.builder().id(UUID.randomUUID()).name("node-cache-directory").path("/cache").build();

    @Test
    void handlesGameFound() {
        assertEquals(EventType.GAME_FOUND, subject.handles());
    }

    @Test
    void listenerRejectsWrongEventType() {
        GameFoundData data = GameFoundData.builder().eventType(EventType.MOVIE_FOUND).gameId(UUID.randomUUID()).build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
        verifyNoInteractions(gameRepository);
    }

    @Test
    void listenerDispatchesMatchingEventType() {
        UUID gameId = UUID.randomUUID();
        when(gameRepository.findById(gameId)).thenReturn(Optional.empty());

        subject.listener(event(gameId));

        verify(gameRepository).findById(gameId);
    }

    @Test
    void gameRowGoneDoesNothing() {
        UUID gameId = UUID.randomUUID();
        when(gameRepository.findById(gameId)).thenReturn(Optional.empty());

        subject.handle(event(gameId));

        verify(gameRepository, never()).save(any());
        verifyNoInteractions(fileAccess, imageRepository, ludusavi, metadataProvider, serverEventService, imageSave);
    }

    // ---- PS3 folders: PARAM.SFO + ICON0.PNG ----

    @Test
    void ps3PatchVersionInTheSfoTitleIsNotPartOfTheTitle() throws IOException {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        game.setTitle("Uncharted: Drake's Fortune™ 1.02"); // what an older scan kept
        byte[] sfo = ParamSfoParserTest.sfo(Map.of("TITLE", "Uncharted: Drake's Fortune™\n1.02", "TITLE_ID", "NPEA00363"));
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/PARAM.SFO")).thenReturn(new ByteArrayInputStream(sfo));
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        when(fileAccess.exists(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(false);
        when(metadataProvider.enrich(game)).thenReturn(false);

        subject.handle(event(game.getId()));

        assertEquals("Uncharted: Drake's Fortune™", game.getTitle(), "a rescan cleans the stored title");
        verify(gameRepository).save(same(game));
    }

    @Test
    void ps3TitleAndTitleIdComeFromParamSfoAndIconBecomesCover() throws IOException {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        byte[] sfo = ParamSfoParserTest.sfo(Map.of("TITLE", "Demon's\nSouls ", "TITLE_ID", "BLUS30443"));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/PARAM.SFO")).thenReturn(new ByteArrayInputStream(sfo));
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        when(fileAccess.exists(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(true);
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(new ByteArrayInputStream(png));
        when(cacheDirectoryResolver.store()).thenReturn(cacheStore);
        when(cacheStore.directory()).thenReturn(cacheDir);
        when(cacheStore.write(startsWith("games/" + game.getId() + "/"), eq(png), eq("image/png")))
                .thenReturn("/cache/games/" + game.getId() + "/icon.png");
        when(metadataProvider.enrich(game)).thenReturn(false);

        subject.handle(event(game.getId()));

        assertEquals("Demon's Souls", game.getTitle(), "newlines folded, stripped");
        assertEquals("BLUS30443", game.getTitleId());
        verify(gameRepository).save(same(game));
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.GAME, game.getId());
        ArgumentCaptor<ImageSave.MediaEntityRef> ref = ArgumentCaptor.forClass(ImageSave.MediaEntityRef.class);
        verify(imageSave).save(same(cacheDir), eq("/cache/games/" + game.getId() + "/icon.png"), eq(ImageType.COVER),
                isNull(), eq("ps3sfo://" + game.getId() + "/ICON0.PNG"), ref.capture());
        assertEquals(game.getId(), ref.getValue().game().getId());
        assertNull(ref.getValue().movie());
        verifyNoInteractions(ludusavi);
    }

    @Test
    void ps3WrapperFolderBetweenRootAndMarkerIsKept() throws IOException {
        GameEntity game = ps3Game("Disc1/PS3_GAME/USRDIR/EBOOT.BIN");
        byte[] sfo = ParamSfoParserTest.sfo(Map.of("TITLE", "Demon's Souls", "TITLE_ID", "BLUS30443"));
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/Disc1/PS3_GAME/PARAM.SFO")).thenReturn(new ByteArrayInputStream(sfo));
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        when(fileAccess.exists(libraryDir, ROOT + "/Disc1/PS3_GAME/ICON0.PNG")).thenReturn(false);

        subject.handle(event(game.getId()));

        assertEquals("BLUS30443", game.getTitleId());
        verify(gameRepository).save(same(game));
        verify(imageSave, never()).save(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(cacheDirectoryResolver);
    }

    @Test
    void ps3UnchangedSfoValuesDoNotSave() throws IOException {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        game.setTitle("Demon's Souls");
        game.setTitleId("BLUS30443");
        byte[] sfo = ParamSfoParserTest.sfo(Map.of("TITLE", "Demon's Souls", "TITLE_ID", "BLUS30443"));
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/PARAM.SFO")).thenReturn(new ByteArrayInputStream(sfo));
        when(imageRepository.findByGameEntityId(game.getId()))
                .thenReturn(List.of(ImageEntity.builder().id(UUID.randomUUID()).build()));

        subject.handle(event(game.getId()));

        verify(gameRepository, never()).save(any());
        verifyNoInteractions(serverEventService, imageSave, cacheDirectoryResolver);
        verify(fileAccess, never()).exists(any(), anyString());
    }

    @Test
    void ps3SfoWithoutTitleKeysChangesNothing() throws IOException {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        byte[] sfo = ParamSfoParserTest.sfo(Map.of("PARENTAL_LEVEL", 9L));
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/PARAM.SFO")).thenReturn(new ByteArrayInputStream(sfo));
        when(imageRepository.findByGameEntityId(game.getId()))
                .thenReturn(List.of(ImageEntity.builder().id(UUID.randomUUID()).build()));

        subject.handle(event(game.getId()));

        assertNull(game.getTitle());
        assertNull(game.getTitleId());
        verify(gameRepository, never()).save(any());
    }

    @Test
    void ps3GarbageSfoIsLoggedNotFatalAndIconStillCopied() throws IOException {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/PARAM.SFO")).thenReturn(new ByteArrayInputStream("garbage".getBytes()));
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        when(fileAccess.exists(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(true);
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(cacheDirectoryResolver.store()).thenReturn(cacheStore);
        when(cacheStore.directory()).thenReturn(cacheDir);
        when(cacheStore.write(anyString(), any(byte[].class), eq("image/png"))).thenReturn("/cache/games/x.png");

        subject.handle(event(game.getId()));

        assertNull(game.getTitle());
        verify(gameRepository, never()).save(any());
        verify(imageSave).save(same(cacheDir), eq("/cache/games/x.png"), eq(ImageType.COVER), isNull(), anyString(), any());
    }

    @Test
    void ps3TrophyIconsDoNotStopTheIconBecomingTheCover() throws IOException {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/PARAM.SFO")).thenThrow(new IOException("disk gone"));
        when(imageRepository.findByGameEntityId(game.getId()))
                .thenReturn(List.of(ImageEntity.builder().id(UUID.randomUUID()).type(ImageType.ICON).build()));
        when(fileAccess.exists(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(true);
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(cacheDirectoryResolver.store()).thenReturn(cacheStore);
        when(cacheStore.directory()).thenReturn(cacheDir);
        when(cacheStore.write(anyString(), any(byte[].class), eq("image/png"))).thenReturn("/cache/games/x.png");

        subject.handle(event(game.getId()));

        verify(imageSave).save(same(cacheDir), eq("/cache/games/x.png"), eq(ImageType.COVER), isNull(), anyString(), any());
    }

    @Test
    void ps3UnreadableSfoIsLoggedNotFatal() throws IOException {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/PARAM.SFO")).thenThrow(new IOException("disk gone"));
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        when(fileAccess.exists(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(false);

        subject.handle(event(game.getId()));

        verify(gameRepository, never()).save(any());
        verifyNoInteractions(serverEventService, imageSave);
    }

    @Test
    void ps3IconCopyFailureIsLoggedNotFatal() throws IOException {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        byte[] sfo = ParamSfoParserTest.sfo(Map.of("TITLE", "Demon's Souls"));
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/PARAM.SFO")).thenReturn(new ByteArrayInputStream(sfo));
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        when(fileAccess.exists(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(true);
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(cacheDirectoryResolver.store()).thenReturn(cacheStore);
        when(cacheStore.write(anyString(), any(byte[].class), eq("image/png"))).thenThrow(new IOException("cache full"));

        subject.handle(event(game.getId()));

        assertEquals("Demon's Souls", game.getTitle(), "the SFO result survives the icon failure");
        verify(gameRepository).save(same(game));
        verify(imageSave, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ps3IconReadFailureIsLoggedNotFatal() throws IOException {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/PARAM.SFO"))
                .thenReturn(new ByteArrayInputStream(ParamSfoParserTest.sfo(Map.of("TITLE", "X"))));
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        when(fileAccess.exists(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(true);
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("truncated");
            }
        };
        when(fileAccess.open(libraryDir, ROOT + "/PS3_GAME/ICON0.PNG")).thenReturn(broken);

        subject.handle(event(game.getId()));

        verify(gameRepository).save(same(game));
        verifyNoInteractions(cacheDirectoryResolver, imageSave);
    }

    @Test
    void ps3EntryPathWithoutMarkerSkipsSfoAndIcon() {
        GameEntity game = ps3Game("USRDIR/EBOOT.BIN");
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());

        subject.handle(event(game.getId()));

        verifyNoInteractions(fileAccess, cacheDirectoryResolver, imageSave);
        verify(gameRepository, never()).save(any());
    }

    @Test
    void ps3WithoutRootPathOrDirectorySkipsSfoAndIcon() {
        GameEntity game = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        game.setRootPath(null);
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());

        subject.handle(event(game.getId()));

        verifyNoInteractions(fileAccess);

        GameEntity noDir = ps3Game("PS3_GAME/USRDIR/EBOOT.BIN");
        noDir.setDirectoryEntity(null);
        when(gameRepository.findById(noDir.getId())).thenReturn(Optional.of(noDir));
        when(imageRepository.findByGameEntityId(noDir.getId())).thenReturn(List.of());

        subject.handle(event(noDir.getId()));

        verifyNoInteractions(fileAccess);
        verify(gameRepository, never()).save(any());
    }

    // ---- PC / Steam: Ludusavi save paths ----

    @Test
    void steamGameResolvesSavePathsBySteamAppId() {
        GameEntity game = pcGame(GameForm.STEAM, "Stardew Valley", null);
        game.setSteamAppId(413150L);
        Entry entry = new Entry("Stardew Valley", List.of(new SavePath("<winAppData>/StardewValley/Saves", "windows")), true, 413150L);
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(ludusavi.find(413150L, "Stardew Valley")).thenReturn(new Match(entry, MatchedBy.STEAM_ID));

        subject.handle(event(game.getId()));

        assertEquals(GameSavePaths.toJson(entry.savePaths()), game.getManifestSavePaths());
        assertEquals(List.of(new SavePath("<winAppData>/StardewValley/Saves", "windows")),
                GameSavePaths.fromJson(game.getManifestSavePaths()));
        assertTrue(game.isSteamCloud());
        assertEquals("STEAM_ID", game.getManifestMatchedBy());
        verify(gameRepository).save(same(game));
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.GAME, game.getId());
        verifyNoInteractions(fileAccess, imageRepository, imageSave);
    }

    @Test
    void pcGameFallsBackToFolderNameWhenDisplayTitleDoesNotMatch() {
        GameEntity game = pcGame(GameForm.PC_PORTABLE_ZIP, "Celeste", "Celeste - Farewell Edition");
        Entry entry = new Entry("Celeste", List.of(new SavePath("<base>/Saves", null)), false, 504230L);
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(ludusavi.find(null, "Celeste - Farewell Edition")).thenReturn(Match.NONE);
        when(ludusavi.find(null, "Celeste")).thenReturn(new Match(entry, MatchedBy.TITLE));

        subject.handle(event(game.getId()));

        assertEquals("TITLE", game.getManifestMatchedBy());
        assertFalse(game.isSteamCloud());
        assertEquals(List.of(new SavePath("<base>/Saves", null)), GameSavePaths.fromJson(game.getManifestSavePaths()));
        verify(gameRepository).save(same(game));
    }

    @Test
    void pcGameWithoutTitleDoesNotRetryTheSameName() {
        GameEntity game = pcGame(GameForm.PC_LINUX_INSTALLER, "Unknown Indie", null);
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(ludusavi.find(null, "Unknown Indie")).thenReturn(Match.NONE);

        subject.handle(event(game.getId()));

        verify(ludusavi).find(null, "Unknown Indie");
        assertNull(game.getManifestSavePaths());
        assertEquals("NONE", game.getManifestMatchedBy());
        verify(gameRepository).save(same(game));
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.GAME, game.getId());
    }

    @Test
    void pcGameAlreadyMarkedNoneIsNotSavedAgain() {
        GameEntity game = pcGame(GameForm.PC_LINUX_PORTABLE, "Unknown Indie", null);
        game.setManifestMatchedBy("NONE");
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(ludusavi.find(null, "Unknown Indie")).thenReturn(Match.NONE);

        subject.handle(event(game.getId()));

        verify(gameRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    @Test
    void manifestEntryDisappearingClearsTheResolvedPaths() {
        GameEntity game = pcGame(GameForm.STEAM, "Gone Game", null);
        game.setSteamAppId(1L);
        game.setManifestSavePaths("[{\"path\":\"<base>/old\",\"os\":null}]");
        game.setSteamCloud(true);
        game.setManifestMatchedBy("STEAM_ID");
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(ludusavi.find(1L, "Gone Game")).thenReturn(Match.NONE);

        subject.handle(event(game.getId()));

        assertNull(game.getManifestSavePaths());
        assertFalse(game.isSteamCloud());
        assertEquals("NONE", game.getManifestMatchedBy());
        verify(gameRepository).save(same(game));
    }

    @Test
    void adminSavePathsAreNotTouchedByTheManifest() {
        GameEntity game = pcGame(GameForm.STEAM, "Stardew Valley", null);
        game.setSteamAppId(413150L);
        game.setSavePaths("[{\"path\":\"<home>/custom\",\"os\":\"linux\"}]");
        Entry entry = new Entry("Stardew Valley", List.of(new SavePath("<winAppData>/StardewValley/Saves", "windows")), false, 413150L);
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(ludusavi.find(413150L, "Stardew Valley")).thenReturn(new Match(entry, MatchedBy.STEAM_ID));

        subject.handle(event(game.getId()));

        assertEquals("[{\"path\":\"<home>/custom\",\"os\":\"linux\"}]", game.getSavePaths());
        assertEquals(GameSavePaths.toJson(entry.savePaths()), game.getManifestSavePaths());
    }

    // ---- The metadata provider seam ----

    @Test
    void romUsesNeitherSfoNorLudusaviAndSavesOnlyWhenTheProviderChangedSomething() {
        GameEntity game = pcGame(GameForm.ROM, "Super Metroid", null);
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(metadataProvider.enrich(game)).thenReturn(true);

        subject.handle(event(game.getId()));

        verify(gameRepository).save(same(game));
        verify(serverEventService).createSearchIndexEvent(SearchEntityType.GAME, game.getId());
        verifyNoInteractions(ludusavi, fileAccess, imageRepository, imageSave, cacheDirectoryResolver);
    }

    @Test
    void romWithNoopProviderChangesNothing() {
        GameEntity game = pcGame(GameForm.ROM, "Super Metroid", null);
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(metadataProvider.enrich(game)).thenReturn(false);

        subject.handle(event(game.getId()));

        verify(gameRepository, never()).save(any());
        verifyNoInteractions(serverEventService);
    }

    @Test
    void providerChangeIsSavedTogetherWithTheManifestResult() {
        GameEntity game = pcGame(GameForm.STEAM, "Stardew Valley", null);
        game.setSteamAppId(413150L);
        game.setManifestMatchedBy("NONE");
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(ludusavi.find(413150L, "Stardew Valley")).thenReturn(Match.NONE);
        when(metadataProvider.enrich(game)).thenAnswer(inv -> {
            game.setReleaseYear(2016);
            return true;
        });

        subject.handle(event(game.getId()));

        assertEquals(2016, game.getReleaseYear());
        verify(gameRepository).save(same(game));
    }

    // ---- helpers ----

    private static GameFoundData event(UUID gameId) {
        return GameFoundData.builder().eventType(EventType.GAME_FOUND).gameId(gameId).build();
    }

    private GameEntity ps3Game(String entryPath) {
        return GameEntity.builder()
                .id(UUID.randomUUID())
                .platform(GamePlatform.PS3)
                .form(GameForm.PS3_FOLDER)
                .name("Demon's Souls")
                .pathYear(2009)
                .directoryEntity(libraryDir)
                .rootPath(ROOT)
                .entryPath(entryPath)
                .build();
    }

    private GameEntity pcGame(GameForm form, String name, String title) {
        return GameEntity.builder()
                .id(UUID.randomUUID())
                .platform(form == GameForm.ROM ? GamePlatform.SNES : GamePlatform.PC)
                .form(form)
                .name(name)
                .title(title)
                .build();
    }
}
