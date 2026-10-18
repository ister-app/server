package app.ister.disk.scanner;

import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GamePathObjectTest {

    private static final String ROOT = "/media/games";

    @Test
    void platformFolderIsRecognised() {
        var path = new GamePathObject(ROOT, ROOT + "/n64", true);
        assertEquals(GamePlatform.N64, path.getPlatform());
        assertEquals(DirType.GAME_PLATFORM, path.getDirType());
        assertNull(path.getGameName());
    }

    @Test
    void unknownPlatformFolderIsIgnored() {
        var path = new GamePathObject(ROOT, ROOT + "/xbox/Halo (2001)/halo.iso", false);
        assertNull(path.getPlatform());
        assertEquals(DirType.NONE, path.getDirType());
        assertEquals(FileType.NONE, path.getFileType());
    }

    @Test
    void gameFolderCarriesNameAndYear() {
        var path = new GamePathObject(ROOT, ROOT + "/n64/Super Mario 64 (1996)", true);
        assertEquals(DirType.GAME, path.getDirType());
        assertEquals("Super Mario 64", path.getGameName());
        assertEquals(1996, path.getPathYear());
        assertEquals(ROOT + "/n64/Super Mario 64 (1996)", path.getGameRootPath());
    }

    @Test
    void romIsTheEntryFile() {
        var path = new GamePathObject(ROOT, ROOT + "/n64/Super Mario 64 (1996)/Super Mario 64.z64", false);
        assertEquals(FileType.GAME, path.getFileType());
        assertEquals(GameForm.ROM, path.getForm());
        assertTrue(path.isEntryFile());
        assertEquals("Super Mario 64", path.getGameName());
        assertEquals("Super Mario 64.z64", path.getRelativeInGame());
    }

    @Test
    void cueIsEntryAndBinIsCompanionOnPsx() {
        var cue = new GamePathObject(ROOT, ROOT + "/psx/Crash (1996)/Crash.cue", false);
        var bin = new GamePathObject(ROOT, ROOT + "/psx/Crash (1996)/Crash.bin", false);
        assertTrue(cue.isEntryFile());
        assertEquals(FileType.GAME, bin.getFileType());
        assertFalse(bin.isEntryFile());
    }

    @Test
    void romDirectlyUnderPlatformFolderIsIgnored() {
        var path = new GamePathObject(ROOT, ROOT + "/gbc/Tetris.gbc", false);
        assertEquals(FileType.NONE, path.getFileType());
        assertNull(path.getGameName());
    }

    @Test
    void gameWithoutYearStillParses() {
        var path = new GamePathObject(ROOT, ROOT + "/gbc/Tetris/Tetris.gbc", false);
        assertEquals("Tetris", path.getGameName());
        assertEquals(0, path.getPathYear());
        assertEquals(FileType.GAME, path.getFileType());
    }

    @Test
    void coverImageInGameFolderIsAnImage() {
        var path = new GamePathObject(ROOT, ROOT + "/gbc/Tetris (1989)/cover.jpg", false);
        assertEquals(FileType.IMAGE, path.getFileType());
        assertEquals("Tetris", path.getGameName());
    }

    @Test
    void nestedFilesOfRomPlatformsAreNotPartOfTheGrammar() {
        var path = new GamePathObject(ROOT, ROOT + "/gbc/Tetris (1989)/extras/manual.pdf", false);
        assertEquals(FileType.NONE, path.getFileType());
        var dir = new GamePathObject(ROOT, ROOT + "/gbc/Tetris (1989)/extras", true);
        assertEquals(DirType.GAME_TREE, dir.getDirType());
        assertFalse(dir.descendIntoTree());
    }

    @Test
    void ps3ParamSfoIsTheMarker() {
        var path = new GamePathObject(ROOT, ROOT + "/ps3/Demon's Souls (2009)/PS3_GAME/PARAM.SFO", false);
        assertEquals(FileType.GAME_MARKER, path.getFileType());
        assertEquals(GameForm.PS3_FOLDER, path.getForm());
        assertEquals("PS3_GAME/USRDIR/EBOOT.BIN", path.ps3EntryPath());
        assertEquals("Demon's Souls", path.getGameName());
        assertTrue(path.descendIntoTree());
    }

    @Test
    void ps3MarkerAcceptsOneWrapperFolder() {
        var path = new GamePathObject(ROOT, ROOT + "/ps3/Demon's Souls (2009)/BLUS30443/PS3_GAME/PARAM.SFO", false);
        assertEquals(FileType.GAME_MARKER, path.getFileType());
        assertEquals("BLUS30443/PS3_GAME/USRDIR/EBOOT.BIN", path.ps3EntryPath());
        var tooDeep = new GamePathObject(ROOT, ROOT + "/ps3/Demon's Souls (2009)/a/b/PS3_GAME/PARAM.SFO", false);
        assertEquals(FileType.GAME_TREE_FILE, tooDeep.getFileType());
    }

    @Test
    void otherPs3FilesAreTreeFiles() {
        var path = new GamePathObject(ROOT, ROOT + "/ps3/Demon's Souls (2009)/PS3_GAME/USRDIR/EBOOT.BIN", false);
        assertEquals(FileType.GAME_TREE_FILE, path.getFileType());
        assertEquals("PS3_GAME/USRDIR/EBOOT.BIN", path.getRelativeInGame());
    }

    @Test
    void steamManifestIsTheMarkerWithItsAppId() {
        var path = new GamePathObject(ROOT, ROOT + "/steam/Stardew Valley (2016)/appmanifest_413150.acf", false);
        assertEquals(FileType.GAME_MARKER, path.getFileType());
        assertEquals(GameForm.STEAM, path.getForm());
        assertEquals(413150L, path.getSteamAppId());
        var tree = new GamePathObject(ROOT, ROOT + "/steam/Stardew Valley (2016)/Stardew Valley/Stardew Valley.exe", false);
        assertEquals(FileType.GAME_TREE_FILE, tree.getFileType());
        assertEquals("Stardew Valley/Stardew Valley.exe", tree.getRelativeInGame());
    }

    @Test
    void pcAndLinuxFormsFollowTheExtension() {
        assertEquals(GameForm.PC_PORTABLE_ZIP, new GamePathObject(ROOT, ROOT + "/pc/Game (2010)/game.zip", false).getForm());
        assertEquals(GameForm.PC_LINUX_INSTALLER, new GamePathObject(ROOT, ROOT + "/linux/Game (2010)/setup_game.sh", false).getForm());
        assertEquals(GameForm.PC_LINUX_PORTABLE, new GamePathObject(ROOT, ROOT + "/linux/Game (2010)/game.tar.xz", false).getForm());
        assertEquals(FileType.NONE, new GamePathObject(ROOT, ROOT + "/pc/Game (2010)/readme.txt", false).getFileType());
    }

    @Test
    void rootWithTrailingSlashWorks() {
        var path = new GamePathObject(ROOT + "/", ROOT + "/gbc/Tetris (1989)/Tetris.gbc", false);
        assertEquals(FileType.GAME, path.getFileType());
    }
}
