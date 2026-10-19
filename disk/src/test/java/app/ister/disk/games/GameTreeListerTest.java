package app.ister.disk.games;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.StorageKind;
import app.ister.core.storage.ObjectStoreRegistry;
import app.ister.disk.storage.FakeObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The live listing of a folder game's tree, for a local folder and for an S3 prefix alike. */
class GameTreeListerTest {

    private static final Instant MODIFIED = Instant.parse("2026-10-01T12:00:00Z");

    @TempDir
    Path tempDir;

    private final ObjectStoreRegistry registry = mock(ObjectStoreRegistry.class);
    private final GameTreeLister lister = new GameTreeLister(registry);

    private static DirectoryEntity local() {
        return DirectoryEntity.builder().name("games").path("/games").directoryType(DirectoryType.LIBRARY).build();
    }

    private static DirectoryEntity s3() {
        return DirectoryEntity.builder().name("games-s3").path("s3://bucket/games").storageKind(StorageKind.S3)
                .directoryType(DirectoryType.LIBRARY).build();
    }

    private Path localTree() throws IOException {
        Path root = tempDir.resolve("Game (2010)");
        Files.createDirectories(root.resolve("PS3_GAME/USRDIR"));
        Files.createDirectories(root.resolve(".ister-upload/session"));
        Files.write(root.resolve("PS3_GAME/PARAM.SFO"), new byte[10]);
        Files.write(root.resolve("PS3_GAME/USRDIR/EBOOT.BIN"), new byte[100]);
        Files.write(root.resolve(".DS_Store"), new byte[5]);
        Files.write(root.resolve(".ister-upload/session/part"), new byte[7]);
        Files.setLastModifiedTime(root.resolve("PS3_GAME/PARAM.SFO"), FileTime.from(MODIFIED));
        return root;
    }

    @Test
    void listsLocalFilesSortedWithSizesAndSkipsDotSegments() throws IOException {
        Path root = localTree();

        List<GameTreeLister.TreeFile> files = lister.list(local(), root.toString());

        assertThat(files).extracting(GameTreeLister.TreeFile::relativePath)
                .containsExactly("PS3_GAME/PARAM.SFO", "PS3_GAME/USRDIR/EBOOT.BIN");
        assertThat(files.getFirst().size()).isEqualTo(10);
        assertThat(files.getFirst().lastModified()).isEqualTo(MODIFIED);
        assertThat(files.get(1).size()).isEqualTo(100);
    }

    @Test
    void aMissingLocalRootListsNothing() throws IOException {
        assertThat(lister.list(local(), tempDir.resolve("nope").toString())).isEmpty();
        assertThat(lister.stats(local(), tempDir.resolve("nope").toString()))
                .isEqualTo(new GameTreeLister.TreeStats(0, 0));
    }

    @Test
    void statsSumTheListedFiles() throws IOException {
        Path root = localTree();

        assertThat(lister.stats(local(), root.toString())).isEqualTo(new GameTreeLister.TreeStats(110, 2));
    }

    @Test
    void isNonEmptyDirectoryForLocalChecksTheFolderExists() throws IOException {
        Path root = localTree();

        assertThat(lister.isNonEmptyDirectory(local(), root.toString(), "PS3_GAME")).isTrue();
        assertThat(lister.isNonEmptyDirectory(local(), root.toString(), "PS3_GAME/PARAM.SFO")).isFalse();
        assertThat(lister.isNonEmptyDirectory(local(), root.toString(), "missing")).isFalse();
    }

    @Test
    void listsAnS3PrefixRelativeToTheGameFolder() throws IOException {
        DirectoryEntity directory = s3();
        FakeObjectStore store = new FakeObjectStore("bucket")
                .put("games/ps3/Game (2010)/PS3_GAME/USRDIR/EBOOT.BIN", "0123456789")
                .put("games/ps3/Game (2010)/PS3_GAME/PARAM.SFO", "abc")
                .put("games/ps3/Game (2010)/.hidden/junk", "x")
                .put("games/ps3/Game (2010)", "")
                .put("games/ps3/Game (2010) Other/PS3_GAME/PARAM.SFO", "zzz");
        when(registry.forDirectory(directory)).thenReturn(store);

        List<GameTreeLister.TreeFile> files = lister.list(directory, "s3://bucket/games/ps3/Game (2010)");

        assertThat(files).extracting(GameTreeLister.TreeFile::relativePath)
                .containsExactly("PS3_GAME/PARAM.SFO", "PS3_GAME/USRDIR/EBOOT.BIN");
        assertThat(files.getFirst().size()).isEqualTo(3);
        assertThat(files.getFirst().lastModified()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(lister.stats(directory, "s3://bucket/games/ps3/Game (2010)"))
                .isEqualTo(new GameTreeLister.TreeStats(13, 2));
    }

    @Test
    void isNonEmptyDirectoryForS3LooksForObjectsOrChildPrefixes() {
        DirectoryEntity directory = s3();
        FakeObjectStore store = new FakeObjectStore("bucket")
                .put("games/ps3/Game (2010)/PS3_GAME/USRDIR/EBOOT.BIN", "0123456789")
                .put("games/ps3/Game (2010)/PS3_GAME/PARAM.SFO", "abc");
        when(registry.forDirectory(directory)).thenReturn(store);
        String root = "s3://bucket/games/ps3/Game (2010)";

        assertThat(lister.isNonEmptyDirectory(directory, root, "PS3_GAME")).as("direct objects").isTrue();
        assertThat(lister.isNonEmptyDirectory(directory, root, "PS3_GAME/USRDIR")).as("direct object").isTrue();
        assertThat(lister.isNonEmptyDirectory(directory, root, "EMPTY")).isFalse();
    }
}
