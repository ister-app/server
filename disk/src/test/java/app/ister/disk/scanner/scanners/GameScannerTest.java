package app.ister.disk.scanner.scanners;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.service.ServerEventService;
import app.ister.core.storage.FileAccess;
import app.ister.disk.games.GameTreeLister;
import app.ister.disk.scanner.Scanners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameScannerTest {

    private static final String ROOT = "/games";
    private static final String ROM_PATH = ROOT + "/n64/Super Mario 64 (1996)/Super Mario 64.z64";
    private static final String CUE_PATH = ROOT + "/psx/Crash Bandicoot (1996)/Crash Bandicoot.cue";
    private static final String BIN_PATH = ROOT + "/psx/Crash Bandicoot (1996)/Crash Bandicoot.bin";
    private static final String PS3_MARKER = ROOT + "/ps3/Demon's Souls (2009)/PS3_GAME/PARAM.SFO";
    private static final String PS3_ROOT = ROOT + "/ps3/Demon's Souls (2009)";
    private static final String STEAM_MARKER = ROOT + "/steam/Half-Life 2 (2004)/appmanifest_220.acf";
    private static final String STEAM_ROOT = ROOT + "/steam/Half-Life 2 (2004)";

    @Mock
    private ScannerHelperService scannerHelperService;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private GameTreeLister treeLister;
    @Mock
    private FileAccess fileAccess;
    @Mock
    private ServerEventService serverEventService;

    @InjectMocks
    private GameScanner subject;

    private LibraryEntity library;
    private DirectoryEntity gameDir;

    @BeforeEach
    void setUp() {
        library = LibraryEntity.builder().id(UUID.randomUUID()).libraryType(LibraryType.GAME).name("Games").build();
        gameDir = DirectoryEntity.builder()
                .id(UUID.randomUUID())
                .libraryEntity(library)
                .name("games-dir")
                .path(ROOT)
                .build();
    }

    private GameEntity game(GamePlatform platform, String name, int year, GameForm form) {
        return GameEntity.builder()
                .id(UUID.randomUUID())
                .libraryEntity(library)
                .platform(platform)
                .name(name)
                .pathYear(year)
                .releaseYear(year)
                .form(form)
                .build();
    }

    // ========== analyzable ==========

    @Nested
    class Analyzable {

        @Test
        void sizeBasedAnalyzableIsAlwaysFalse() {
            assertFalse(subject.analyzable(ROM_PATH, true, 1000));
            assertFalse(subject.analyzable(PS3_MARKER, true, 1000));
        }

        @Test
        void acceptsRomEntryFileInGameLibrary() {
            assertTrue(subject.analyzable(ROM_PATH, true, gameDir));
        }

        @Test
        void acceptsRomCompanionFile() {
            assertTrue(subject.analyzable(BIN_PATH, true, gameDir));
        }

        @Test
        void acceptsPcZipAndLinuxForms() {
            assertTrue(subject.analyzable(ROOT + "/pc/Doom (1993)/Doom.zip", true, gameDir));
            assertTrue(subject.analyzable(ROOT + "/linux/Stardew Valley/setup_stardew.sh", true, gameDir));
            assertTrue(subject.analyzable(ROOT + "/linux/Stardew Valley/stardew.tar.xz", true, gameDir));
        }

        @Test
        void acceptsPs3AndSteamMarkers() {
            assertTrue(subject.analyzable(PS3_MARKER, true, gameDir));
            assertTrue(subject.analyzable(ROOT + "/ps3/Demon's Souls (2009)/BLUS30443/PS3_GAME/PARAM.SFO", true, gameDir));
            assertTrue(subject.analyzable(STEAM_MARKER, true, gameDir));
        }

        @Test
        void rejectsDirectories() {
            assertFalse(subject.analyzable(PS3_ROOT, false, gameDir));
            assertFalse(subject.analyzable(ROOT + "/n64", false, gameDir));
        }

        @Test
        void rejectsWhenDirectoryHasNoLibrary() {
            DirectoryEntity dir = DirectoryEntity.builder().path(ROOT).build();
            assertFalse(subject.analyzable(ROM_PATH, true, dir));
        }

        @Test
        void rejectsNonGameLibrary() {
            LibraryEntity movies = LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies").build();
            DirectoryEntity dir = DirectoryEntity.builder().libraryEntity(movies).path(ROOT).build();
            assertFalse(subject.analyzable(ROM_PATH, true, dir));
        }

        @Test
        void rejectsCoverImagesAndTreeFiles() {
            assertFalse(subject.analyzable(ROOT + "/n64/Super Mario 64 (1996)/cover.jpg", true, gameDir));
            assertFalse(subject.analyzable(ROOT + "/ps3/Demon's Souls (2009)/PS3_GAME/USRDIR/EBOOT.BIN", true, gameDir));
            assertFalse(subject.analyzable(STEAM_ROOT + "/hl2/hl2.exe", true, gameDir));
        }

        @Test
        void rejectsLooseFilesUnknownPlatformsAndForeignPaths() {
            assertFalse(subject.analyzable(ROOT + "/n64/loose.z64", true, gameDir));
            assertFalse(subject.analyzable(ROOT + "/README.txt", true, gameDir));
            assertFalse(subject.analyzable(ROOT + "/atari/Pong/pong.bin", true, gameDir));
            assertFalse(subject.analyzable("/other/n64/Super Mario 64 (1996)/Super Mario 64.z64", true, gameDir));
            assertFalse(subject.analyzable(ROOT + "/n64/Game/deeper/game.z64", true, gameDir));
        }

        @Test
        void scannersWiringAsksGameScannerForGameLibraryEntries() {
            Scanners scanners = new Scanners(mock(MediaFileScanner.class), mock(ImageScanner.class),
                    mock(NfoScanner.class), mock(SubtitleScanner.class), mock(AudioScanner.class),
                    mock(EpubScanner.class), mock(ComicScanner.class), subject);

            assertEquals(List.of(subject), scanners.analyzableBy(gameDir, ROM_PATH, true, 10));
            assertEquals(List.of(subject), scanners.analyzableBy(gameDir, PS3_MARKER, true, 10));
            assertTrue(scanners.analyzableBy(gameDir, PS3_ROOT + "/PS3_GAME/USRDIR/EBOOT.BIN", true, 10).isEmpty());
        }
    }

    // ========== analyze: single-file forms ==========

    @Nested
    class SingleFile {

        @Test
        void unparseablePathYieldsEmptyWithoutTouchingRepositories() {
            Optional<BaseEntity> result = subject.analyze(gameDir, ROOT + "/n64/Super Mario 64 (1996)/cover.jpg", true, 10);

            assertTrue(result.isEmpty());
            verifyNoInteractions(scannerHelperService, gameRepository, mediaFileRepository, serverEventService);
        }

        @Test
        void newRomCreatesEntryMediaFileAndRefreshesTotals() {
            GameEntity game = game(GamePlatform.N64, "Super Mario 64", 1996, GameForm.ROM);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.N64, "Super Mario 64", 1996, GameForm.ROM))
                    .thenReturn(game);
            when(mediaFileRepository.findByDirectoryEntityAndPath(gameDir, ROM_PATH)).thenReturn(Optional.empty());
            when(mediaFileRepository.findByGameEntityId(game.getId()))
                    .thenReturn(List.of(MediaFileEntity.builder().path(ROM_PATH).size(8_388_608L).build()));

            Optional<BaseEntity> result = subject.analyze(gameDir, ROM_PATH, true, 8_388_608L);

            assertTrue(result.isPresent());
            assertSame(game, result.get());
            ArgumentCaptor<MediaFileEntity> saved = ArgumentCaptor.forClass(MediaFileEntity.class);
            verify(mediaFileRepository).save(saved.capture());
            assertEquals(ROM_PATH, saved.getValue().getPath());
            assertEquals(gameDir.getId(), saved.getValue().getDirectoryEntityId());
            assertSame(game, saved.getValue().getGameEntity());
            assertTrue(saved.getValue().isGameEntryFile());
            assertEquals(8_388_608L, saved.getValue().getSize());
            assertEquals(8_388_608L, game.getSizeBytes());
            assertEquals(1, game.getFileCount());
            assertEquals(GameForm.ROM, game.getForm());
            assertNull(game.getRootPath());
            verify(gameRepository).save(game);
            // The creation event comes from the helper; the scanner itself publishes nothing for single files.
            verifyNoInteractions(serverEventService);
        }

        @Test
        void companionBinIsAttachedAsNonEntryFileAndTotalsCoverTheSet() {
            GameEntity game = game(GamePlatform.PSX, "Crash Bandicoot", 1996, GameForm.ROM);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.PSX, "Crash Bandicoot", 1996, GameForm.ROM))
                    .thenReturn(game);
            when(mediaFileRepository.findByDirectoryEntityAndPath(gameDir, BIN_PATH)).thenReturn(Optional.empty());
            when(mediaFileRepository.findByGameEntityId(game.getId())).thenReturn(List.of(
                    MediaFileEntity.builder().path(CUE_PATH).size(1_000L).gameEntryFile(true).build(),
                    MediaFileEntity.builder().path(BIN_PATH).size(700_000_000L).gameEntryFile(false).build()));

            subject.analyze(gameDir, BIN_PATH, true, 700_000_000L);

            ArgumentCaptor<MediaFileEntity> saved = ArgumentCaptor.forClass(MediaFileEntity.class);
            verify(mediaFileRepository).save(saved.capture());
            assertFalse(saved.getValue().isGameEntryFile());
            assertEquals(700_001_000L, game.getSizeBytes());
            assertEquals(2, game.getFileCount());
        }

        @Test
        void entryFileCorrectsTheGameFormForPcZip() {
            String path = ROOT + "/pc/Doom (1993)/Doom.zip";
            GameEntity game = game(GamePlatform.PC, "Doom", 1993, GameForm.ROM);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.PC, "Doom", 1993, GameForm.PC_PORTABLE_ZIP))
                    .thenReturn(game);
            when(mediaFileRepository.findByDirectoryEntityAndPath(gameDir, path)).thenReturn(Optional.empty());
            when(mediaFileRepository.findByGameEntityId(game.getId())).thenReturn(List.of());

            subject.analyze(gameDir, path, true, 5);

            assertEquals(GameForm.PC_PORTABLE_ZIP, game.getForm());
            assertEquals(0, game.getSizeBytes());
            assertEquals(0, game.getFileCount());
        }

        @Test
        void linuxInstallerAndPortableArchiveGetTheirForms() {
            String installer = ROOT + "/linux/Stardew Valley/setup_stardew.sh";
            String archive = ROOT + "/linux/Celeste/celeste.tar.xz";
            GameEntity stardew = game(GamePlatform.LINUX, "Stardew Valley", 0, GameForm.PC_LINUX_INSTALLER);
            GameEntity celeste = game(GamePlatform.LINUX, "Celeste", 0, GameForm.PC_LINUX_PORTABLE);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.LINUX, "Stardew Valley", 0, GameForm.PC_LINUX_INSTALLER))
                    .thenReturn(stardew);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.LINUX, "Celeste", 0, GameForm.PC_LINUX_PORTABLE))
                    .thenReturn(celeste);
            when(mediaFileRepository.findByDirectoryEntityAndPath(eq(gameDir), anyString())).thenReturn(Optional.empty());
            when(mediaFileRepository.findByGameEntityId(any())).thenReturn(List.of());

            Optional<BaseEntity> first = subject.analyze(gameDir, installer, true, 1);
            Optional<BaseEntity> second = subject.analyze(gameDir, archive, true, 2);

            assertSame(stardew, first.orElseThrow());
            assertSame(celeste, second.orElseThrow());
            assertEquals(GameForm.PC_LINUX_INSTALLER, stardew.getForm());
            assertEquals(GameForm.PC_LINUX_PORTABLE, celeste.getForm());
            verify(gameRepository).save(stardew);
            verify(gameRepository).save(celeste);
        }

        @Test
        void unchangedExistingFileIsNotSavedAgain() {
            GameEntity game = game(GamePlatform.N64, "Super Mario 64", 1996, GameForm.ROM);
            MediaFileEntity existing = MediaFileEntity.builder()
                    .path(ROM_PATH).gameEntity(game).gameEntryFile(true).size(42L).build();
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.N64, "Super Mario 64", 1996, GameForm.ROM))
                    .thenReturn(game);
            when(mediaFileRepository.findByDirectoryEntityAndPath(gameDir, ROM_PATH)).thenReturn(Optional.of(existing));
            when(mediaFileRepository.findByGameEntityId(game.getId())).thenReturn(List.of(existing));

            Optional<BaseEntity> result = subject.analyze(gameDir, ROM_PATH, true, 42L);

            assertSame(game, result.orElseThrow());
            verify(mediaFileRepository, never()).save(any());
            verify(gameRepository).save(game);
            assertEquals(42L, game.getSizeBytes());
        }

        @Test
        void existingFileWithWrongGameIsReassociated() {
            GameEntity game = game(GamePlatform.N64, "Super Mario 64", 1996, GameForm.ROM);
            GameEntity other = game(GamePlatform.N64, "Other", 0, GameForm.ROM);
            MediaFileEntity existing = MediaFileEntity.builder()
                    .path(ROM_PATH).gameEntity(other).gameEntryFile(true).size(42L).build();
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.N64, "Super Mario 64", 1996, GameForm.ROM))
                    .thenReturn(game);
            when(mediaFileRepository.findByDirectoryEntityAndPath(gameDir, ROM_PATH)).thenReturn(Optional.of(existing));
            when(mediaFileRepository.findByGameEntityId(game.getId())).thenReturn(List.of(existing));

            subject.analyze(gameDir, ROM_PATH, true, 42L);

            verify(mediaFileRepository).save(existing);
            assertSame(game, existing.getGameEntity());
        }

        @Test
        void existingFileWithoutGameIsAttached() {
            GameEntity game = game(GamePlatform.N64, "Super Mario 64", 1996, GameForm.ROM);
            MediaFileEntity existing = MediaFileEntity.builder()
                    .path(ROM_PATH).gameEntity(null).gameEntryFile(true).size(42L).build();
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.N64, "Super Mario 64", 1996, GameForm.ROM))
                    .thenReturn(game);
            when(mediaFileRepository.findByDirectoryEntityAndPath(gameDir, ROM_PATH)).thenReturn(Optional.of(existing));
            when(mediaFileRepository.findByGameEntityId(game.getId())).thenReturn(List.of(existing));

            subject.analyze(gameDir, ROM_PATH, true, 42L);

            verify(mediaFileRepository).save(existing);
            assertSame(game, existing.getGameEntity());
        }

        @Test
        void existingFileWithChangedSizeOrEntryFlagIsUpdated() {
            GameEntity game = game(GamePlatform.PSX, "Crash Bandicoot", 1996, GameForm.ROM);
            MediaFileEntity existing = MediaFileEntity.builder()
                    .path(BIN_PATH).gameEntity(game).gameEntryFile(true).size(1L).build();
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.PSX, "Crash Bandicoot", 1996, GameForm.ROM))
                    .thenReturn(game);
            when(mediaFileRepository.findByDirectoryEntityAndPath(gameDir, BIN_PATH)).thenReturn(Optional.of(existing));
            when(mediaFileRepository.findByGameEntityId(game.getId())).thenReturn(List.of(existing));

            subject.analyze(gameDir, BIN_PATH, true, 99L);

            verify(mediaFileRepository).save(existing);
            assertFalse(existing.isGameEntryFile());
            assertEquals(99L, existing.getSize());
            assertEquals(99L, game.getSizeBytes());
        }
    }

    // ========== analyze: PS3 folder form ==========

    @Nested
    class Ps3Folder {

        @Test
        void newPs3GameIsOneRowWithRootPathAndNoPerFileRows() throws IOException {
            GameEntity game = game(GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, PS3_ROOT)).thenReturn(new GameTreeLister.TreeStats(7_000_000_000L, 1234));

            Optional<BaseEntity> result = subject.analyze(gameDir, PS3_MARKER, true, 1000);

            assertSame(game, result.orElseThrow());
            assertEquals(GameForm.PS3_FOLDER, game.getForm());
            assertSame(gameDir, game.getDirectoryEntity());
            assertEquals(PS3_ROOT, game.getRootPath());
            assertEquals("PS3_GAME/USRDIR/EBOOT.BIN", game.getEntryPath());
            assertEquals(7_000_000_000L, game.getSizeBytes());
            assertEquals(1234, game.getFileCount());
            verify(gameRepository).save(game);
            verify(serverEventService).createGameFoundEvent(game.getId());
            verifyNoInteractions(mediaFileRepository, fileAccess);
        }

        @Test
        void wrapperFolderIsKeptInEntryPath() throws IOException {
            String marker = PS3_ROOT + "/BLUS30443/PS3_GAME/PARAM.SFO";
            GameEntity game = game(GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, PS3_ROOT)).thenReturn(new GameTreeLister.TreeStats(10, 2));

            subject.analyze(gameDir, marker, true, 1000);

            assertEquals(PS3_ROOT, game.getRootPath());
            assertEquals("BLUS30443/PS3_GAME/USRDIR/EBOOT.BIN", game.getEntryPath());
        }

        @Test
        void unchangedPs3GameIsSavedButNotRepublished() throws IOException {
            GameEntity game = game(GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER);
            game.setDirectoryEntity(gameDir);
            game.setRootPath(PS3_ROOT);
            game.setEntryPath("PS3_GAME/USRDIR/EBOOT.BIN");
            game.setSizeBytes(10);
            game.setFileCount(2);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, PS3_ROOT)).thenReturn(new GameTreeLister.TreeStats(10, 2));

            Optional<BaseEntity> result = subject.analyze(gameDir, PS3_MARKER, true, 1000);

            assertTrue(result.isPresent());
            verify(gameRepository).save(game);
            verify(serverEventService, never()).createGameFoundEvent(any());
        }

        @Test
        void changedTreeRepublishesGameFound() throws IOException {
            GameEntity game = game(GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER);
            game.setDirectoryEntity(gameDir);
            game.setRootPath(PS3_ROOT);
            game.setEntryPath("PS3_GAME/USRDIR/EBOOT.BIN");
            game.setSizeBytes(10);
            game.setFileCount(2);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, PS3_ROOT)).thenReturn(new GameTreeLister.TreeStats(20, 3));

            subject.analyze(gameDir, PS3_MARKER, true, 1000);

            assertEquals(20, game.getSizeBytes());
            assertEquals(3, game.getFileCount());
            verify(serverEventService).createGameFoundEvent(game.getId());
        }

        @Test
        void unreadableTreeKeepsPreviousTotals() throws IOException {
            GameEntity game = game(GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER);
            game.setDirectoryEntity(gameDir);
            game.setRootPath(PS3_ROOT);
            game.setEntryPath("PS3_GAME/USRDIR/EBOOT.BIN");
            game.setSizeBytes(10);
            game.setFileCount(2);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, PS3_ROOT)).thenThrow(new IOException("disk gone"));

            Optional<BaseEntity> result = subject.analyze(gameDir, PS3_MARKER, true, 1000);

            assertTrue(result.isPresent());
            assertEquals(10, game.getSizeBytes());
            assertEquals(2, game.getFileCount());
            verify(gameRepository).save(game);
            verify(serverEventService, never()).createGameFoundEvent(any());
        }

        @Test
        void gameFoundWithWrongFormIsCorrectedAndRepublished() throws IOException {
            GameEntity game = game(GamePlatform.PS3, "Demon's Souls", 2009, GameForm.ROM);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.PS3, "Demon's Souls", 2009, GameForm.PS3_FOLDER))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, PS3_ROOT)).thenReturn(new GameTreeLister.TreeStats(0, 0));

            subject.analyze(gameDir, PS3_MARKER, true, 1000);

            assertEquals(GameForm.PS3_FOLDER, game.getForm());
            verify(serverEventService).createGameFoundEvent(game.getId());
        }
    }

    // ========== analyze: Steam folder form ==========

    @Nested
    class Steam {

        private static final String MANIFEST = """
                "AppState"
                {
                    "appid"      "220"
                    "name"       "Half-Life 2"
                    "installdir" "Half-Life 2"
                    "buildid"    "9876543"
                }
                """;

        private void manifest(String text) throws IOException {
            when(fileAccess.open(gameDir, STEAM_MARKER)).thenReturn(stream(text));
        }

        private static InputStream stream(String text) {
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void newSteamGameReadsManifestAndSizesTheInstallDir() throws IOException {
            manifest(MANIFEST);
            when(treeLister.isNonEmptyDirectory(gameDir, STEAM_ROOT, "Half-Life 2")).thenReturn(true);
            GameEntity game = game(GamePlatform.STEAM, "Half-Life 2", 2004, GameForm.STEAM);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.STEAM, "Half-Life 2", 2004, GameForm.STEAM))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, STEAM_ROOT + "/Half-Life 2")).thenReturn(new GameTreeLister.TreeStats(4_000, 40));

            Optional<BaseEntity> result = subject.analyze(gameDir, STEAM_MARKER, true, 500);

            assertSame(game, result.orElseThrow());
            assertEquals(GameForm.STEAM, game.getForm());
            assertSame(gameDir, game.getDirectoryEntity());
            assertEquals(STEAM_ROOT, game.getRootPath());
            assertEquals(220L, game.getSteamAppId());
            assertEquals("Half-Life 2", game.getSteamInstallDir());
            assertEquals(9876543L, game.getSteamBuildId());
            assertEquals("Half-Life 2", game.getTitle());
            assertNull(game.getEntryPath());
            assertEquals(4_000, game.getSizeBytes());
            assertEquals(40, game.getFileCount());
            verify(gameRepository).save(game);
            verify(serverEventService).createGameFoundEvent(game.getId());
            verifyNoInteractions(mediaFileRepository);
        }

        @Test
        void existingTitleIsNotOverwrittenAndUnchangedGameIsNotRepublished() throws IOException {
            manifest(MANIFEST);
            when(treeLister.isNonEmptyDirectory(gameDir, STEAM_ROOT, "Half-Life 2")).thenReturn(true);
            GameEntity game = game(GamePlatform.STEAM, "Half-Life 2", 2004, GameForm.STEAM);
            game.setDirectoryEntity(gameDir);
            game.setRootPath(STEAM_ROOT);
            game.setTitle("Half-Life 2: Metadata Title");
            game.setSteamAppId(220L);
            game.setSteamInstallDir("Half-Life 2");
            game.setSteamBuildId(9876543L);
            game.setSizeBytes(4_000);
            game.setFileCount(40);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.STEAM, "Half-Life 2", 2004, GameForm.STEAM))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, STEAM_ROOT + "/Half-Life 2")).thenReturn(new GameTreeLister.TreeStats(4_000, 40));

            subject.analyze(gameDir, STEAM_MARKER, true, 500);

            assertEquals("Half-Life 2: Metadata Title", game.getTitle());
            verify(gameRepository).save(game);
            verify(serverEventService, never()).createGameFoundEvent(any());
        }

        @Test
        void newBuildIdRepublishesGameFound() throws IOException {
            manifest(MANIFEST);
            when(treeLister.isNonEmptyDirectory(gameDir, STEAM_ROOT, "Half-Life 2")).thenReturn(true);
            GameEntity game = game(GamePlatform.STEAM, "Half-Life 2", 2004, GameForm.STEAM);
            game.setDirectoryEntity(gameDir);
            game.setRootPath(STEAM_ROOT);
            game.setTitle("Half-Life 2");
            game.setSteamAppId(220L);
            game.setSteamInstallDir("Half-Life 2");
            game.setSteamBuildId(1L);
            game.setSizeBytes(4_000);
            game.setFileCount(40);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.STEAM, "Half-Life 2", 2004, GameForm.STEAM))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, STEAM_ROOT + "/Half-Life 2")).thenReturn(new GameTreeLister.TreeStats(4_000, 40));

            subject.analyze(gameDir, STEAM_MARKER, true, 500);

            assertEquals(9876543L, game.getSteamBuildId());
            verify(serverEventService).createGameFoundEvent(game.getId());
        }

        @Test
        void appIdFallsBackToTheManifestFileName() throws IOException {
            manifest("""
                    "AppState"
                    {
                        "installdir" "Half-Life 2"
                    }
                    """);
            when(treeLister.isNonEmptyDirectory(gameDir, STEAM_ROOT, "Half-Life 2")).thenReturn(true);
            GameEntity game = game(GamePlatform.STEAM, "Half-Life 2", 2004, GameForm.STEAM);
            when(scannerHelperService.getOrCreateGame(library, GamePlatform.STEAM, "Half-Life 2", 2004, GameForm.STEAM))
                    .thenReturn(game);
            when(treeLister.stats(gameDir, STEAM_ROOT + "/Half-Life 2")).thenReturn(new GameTreeLister.TreeStats(1, 1));

            subject.analyze(gameDir, STEAM_MARKER, true, 500);

            assertEquals(220L, game.getSteamAppId());
            assertNull(game.getSteamBuildId());
            assertNull(game.getTitle());
        }

        @Test
        void unreadableManifestIsNotAGame() throws IOException {
            when(fileAccess.open(gameDir, STEAM_MARKER)).thenThrow(new IOException("gone"));

            Optional<BaseEntity> result = subject.analyze(gameDir, STEAM_MARKER, true, 500);

            assertTrue(result.isEmpty());
            verifyNoInteractions(scannerHelperService, gameRepository, serverEventService);
        }

        @Test
        void malformedManifestIsNotAGame() throws IOException {
            manifest("\"AppState\" { \"appid\" \"220\" ");

            Optional<BaseEntity> result = subject.analyze(gameDir, STEAM_MARKER, true, 500);

            assertTrue(result.isEmpty());
            verifyNoInteractions(scannerHelperService, gameRepository, serverEventService);
        }

        @Test
        void manifestWithoutInstallDirIsNotAGame() throws IOException {
            manifest("\"AppState\" { \"appid\" \"220\" \"name\" \"Half-Life 2\" }");

            Optional<BaseEntity> result = subject.analyze(gameDir, STEAM_MARKER, true, 500);

            assertTrue(result.isEmpty());
            verifyNoInteractions(scannerHelperService, treeLister);
        }

        @Test
        void installDirWithPathSeparatorIsRejected() throws IOException {
            manifest("\"AppState\" { \"appid\" \"220\" \"installdir\" \"../etc\" }");

            assertTrue(subject.analyze(gameDir, STEAM_MARKER, true, 500).isEmpty());

            manifest("\"AppState\" { \"appid\" \"220\" \"installdir\" \"a\\\\b\" }");

            assertTrue(subject.analyze(gameDir, STEAM_MARKER, true, 500).isEmpty());
            verifyNoInteractions(scannerHelperService, treeLister);
        }

        @Test
        void nonPositiveAppIdIsRejected() throws IOException {
            manifest("\"AppState\" { \"appid\" \"0\" \"installdir\" \"Half-Life 2\" }");

            assertTrue(subject.analyze(gameDir, STEAM_MARKER, true, 500).isEmpty());
            verifyNoInteractions(scannerHelperService, treeLister);
        }

        @Test
        void missingInstallFolderIsNotAGameYet() throws IOException {
            manifest(MANIFEST);
            when(treeLister.isNonEmptyDirectory(gameDir, STEAM_ROOT, "Half-Life 2")).thenReturn(false);

            Optional<BaseEntity> result = subject.analyze(gameDir, STEAM_MARKER, true, 500);

            assertTrue(result.isEmpty());
            verifyNoInteractions(scannerHelperService, gameRepository, serverEventService);
        }
    }
}
