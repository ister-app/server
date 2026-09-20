package app.ister.core.storage;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.DirectoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLibraryWriteStoreTest {

    @TempDir
    Path root;

    private LocalLibraryWriteStore store;
    private final UUID sessionId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store = new LocalLibraryWriteStore(DirectoryEntity.builder()
                .name("shows").path(root.toString()).directoryType(DirectoryType.LIBRARY).build());
    }

    private String target(String relative) {
        return root.resolve(relative).toString();
    }

    private Path partFile() {
        return root.resolve(LibraryWriteStore.STAGING_DIR).resolve(sessionId.toString()).resolve(fileId + ".part");
    }

    private static ByteArrayInputStream body(String content) {
        return new ByteArrayInputStream(content.getBytes());
    }

    @Test
    void assemblesChunksAndMovesIntoPlace() throws IOException {
        String target = target("Show (2019)/Season 01/Show - s01e01.mkv");
        LibraryWriteStore.Staged staged = store.begin(sessionId, fileId, target, 10);

        assertNull(store.writeChunk(staged, 0, 1, body("hello"), 5));
        assertFalse(store.exists(target), "nothing appears at the target before complete");
        store.writeChunk(staged, 5, 2, body("world"), 5);
        store.complete(staged, List.of(), 10, false);

        assertArrayEquals("helloworld".getBytes(), Files.readAllBytes(Path.of(target)));
        assertFalse(Files.exists(partFile()));
    }

    @Test
    void discardsTheTailOfABrokenChunkOnRetry() throws IOException {
        LibraryWriteStore.Staged staged = store.begin(sessionId, fileId, target("a.mkv"), 10);
        store.writeChunk(staged, 0, 1, body("hello"), 5);

        // the connection drops after 2 of the 5 announced bytes
        assertThrows(IOException.class, () -> store.writeChunk(staged, 5, 2, body("wo"), 5));
        assertEquals(5, Files.size(partFile()));

        store.writeChunk(staged, 5, 2, body("world"), 5);
        store.complete(staged, List.of(), 10, false);
        assertArrayEquals("helloworld".getBytes(), Files.readAllBytes(root.resolve("a.mkv")));
    }

    @Test
    void refusesAChunkBeyondWhatIsStaged() throws IOException {
        LibraryWriteStore.Staged staged = store.begin(sessionId, fileId, target("a.mkv"), 10);
        assertThrows(IOException.class, () -> store.writeChunk(staged, 5, 2, body("world"), 5));
    }

    @Test
    void refusesToCompleteWithTheWrongSize() throws IOException {
        LibraryWriteStore.Staged staged = store.begin(sessionId, fileId, target("a.mkv"), 10);
        store.writeChunk(staged, 0, 1, body("hello"), 5);
        assertThrows(IOException.class, () -> store.complete(staged, List.of(), 10, false));
        assertFalse(store.exists(target("a.mkv")));
    }

    @Test
    void keepsAnExistingTargetUnlessOverwriteIsOn() throws IOException {
        Files.writeString(root.resolve("a.mkv"), "old");
        LibraryWriteStore.Staged staged = store.begin(sessionId, fileId, target("a.mkv"), 3);
        store.writeChunk(staged, 0, 1, body("new"), 3);

        assertThrows(FileAlreadyExistsException.class, () -> store.complete(staged, List.of(), 3, false));
        assertEquals("old", Files.readString(root.resolve("a.mkv")));

        store.complete(staged, List.of(), 3, true);
        assertEquals("new", Files.readString(root.resolve("a.mkv")));
    }

    @Test
    void completesAnEmptyFileWithoutAnyChunk() throws IOException {
        LibraryWriteStore.Staged staged = store.begin(sessionId, fileId, target("Artist/Album/empty.nfo"), 0);
        store.complete(staged, List.of(), 0, false);
        assertEquals(0, Files.size(root.resolve("Artist/Album/empty.nfo")));
    }

    @Test
    void refusesATargetBehindASymlinkLeavingTheDirectory(@TempDir Path elsewhere) throws IOException {
        Files.createSymbolicLink(root.resolve("Escape"), elsewhere);
        LibraryWriteStore.Staged staged = store.begin(sessionId, fileId, target("Escape/New Folder/a.mkv"), 3);
        store.writeChunk(staged, 0, 1, body("new"), 3);

        assertThrows(IOException.class, () -> store.complete(staged, List.of(), 3, false));
        assertFalse(Files.exists(elsewhere.resolve("New Folder")), "no folder is created on the far side of the link");
    }

    @Test
    void abortRemovesStagedBytes() throws IOException {
        LibraryWriteStore.Staged staged = store.begin(sessionId, fileId, target("a.mkv"), 10);
        store.writeChunk(staged, 0, 1, body("hello"), 5);
        assertTrue(Files.exists(partFile()));

        store.abort(staged);
        assertFalse(Files.exists(partFile()));

        store.abortSession(sessionId);
        assertFalse(Files.exists(partFile().getParent()));
    }

    @Test
    void reportsSpaceAndWritability() {
        assertTrue(store.usableSpace().isPresent());
        assertTrue(store.writable());
    }
}
