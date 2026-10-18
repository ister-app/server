package app.ister.core.storage;

import app.ister.core.entity.DirectoryEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@link LibraryWriteStore} on a LOCAL directory. Chunks are appended to
 * {@code <root>/.ister-upload/<session>/<file>.part}: on the same filesystem as the target, so
 * completing is an atomic move rather than a second copy of a 40 GB file.
 *
 * <p>Chunks are not fsynced one by one: the page cache survives a JVM restart, and a power loss
 * is recovered from by {@link #stagedBytes} — the caller lowers its idea of the file to what is
 * really on disk and the client resends from there. {@link #complete} syncs the part and its
 * target folder once, so a COMPLETED file is on disk when its row commits.
 */
public class LocalLibraryWriteStore implements LibraryWriteStore {

    /** Big enough that the copy loop is not the bottleneck at line rate; direct, so the channel does not copy again. */
    private static final int BUFFER_SIZE = 1 << 20;

    private final DirectoryEntity directory;
    private final Path root;

    public LocalLibraryWriteStore(DirectoryEntity directory) {
        this.directory = directory;
        this.root = Path.of(directory.getPath());
    }

    @Override
    public DirectoryEntity directory() {
        return directory;
    }

    @Override
    public boolean exists(String targetPath) {
        return Files.exists(Path.of(targetPath));
    }

    private Path sessionDir(UUID sessionId) {
        return root.resolve(STAGING_DIR).resolve(sessionId.toString());
    }

    private Path partFile(Staged staged) {
        return sessionDir(staged.sessionId()).resolve(staged.fileId() + ".part");
    }

    @Override
    public Staged begin(UUID sessionId, UUID fileId, String targetPath, long size) throws IOException {
        Files.createDirectories(sessionDir(sessionId));
        return new Staged(sessionId, fileId, targetPath, null);
    }

    @Override
    public String writeChunk(Staged staged, long offset, int partNumber, InputStream body, long length)
            throws IOException {
        Path part = partFile(staged);
        Files.createDirectories(part.getParent());
        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            if (channel.size() < offset) {
                throw new IOException("Staged bytes of " + staged.targetPath() + " are gone: have "
                        + channel.size() + ", chunk continues at " + offset);
            }
            // Whatever lies beyond the offset is the tail of a chunk that never finished.
            channel.truncate(offset);
            channel.position(offset);
            long written = copy(Channels.newChannel(body), channel, length);
            if (written != length) {
                channel.truncate(offset);
                throw new IOException("Chunk of " + staged.targetPath() + " broke off after " + written
                        + " of " + length + " bytes");
            }
        }
        return null;
    }

    @Override
    public OptionalLong stagedBytes(Staged staged) throws IOException {
        Path part = partFile(staged);
        return OptionalLong.of(Files.exists(part) ? Files.size(part) : 0L);
    }

    private static long copy(ReadableByteChannel in, FileChannel out, long length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) Math.min(BUFFER_SIZE, length));
        long total = 0;
        while (total < length) {
            buffer.clear();
            buffer.limit((int) Math.min(BUFFER_SIZE, length - total));
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                out.write(buffer);
            }
            total += read;
        }
        return total;
    }

    @Override
    public void complete(Staged staged, List<ObjectStore.UploadedPart> parts, long expectedSize, boolean overwrite)
            throws IOException {
        Path part = partFile(staged);
        Path target = Path.of(staged.targetPath());
        if (!Files.exists(part) && expectedSize > 0 && Files.isRegularFile(target) && Files.size(target) == expectedSize) {
            // A retry: the move already happened, only the caller's bookkeeping did not make it.
            return;
        }
        if (expectedSize == 0 && !Files.exists(part)) {
            Files.createDirectories(part.getParent());
            Files.createFile(part);
        }
        long stagedBytes = Files.size(part);
        if (stagedBytes != expectedSize) {
            throw new IOException("Staged " + stagedBytes + " bytes for " + target + ", expected " + expectedSize);
        }
        if (!overwrite && Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        requireInsideRoot(target.getParent());
        Files.createDirectories(target.getParent());
        // The one fsync of the upload: the chunks only went to the page cache.
        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            if (overwrite) {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // Without REPLACE_EXISTING an atomic move may still replace silently on POSIX;
                // the exists check above is the guard, this is the move.
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException _) {
            if (overwrite) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(part, target);
            }
        }
        syncDirectory(target.getParent());
    }

    /** Makes the rename itself durable; not every filesystem lets a directory be opened for that, then it is skipped. */
    private static void syncDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException _) {
            // best effort
        }
    }

    /**
     * The string-level containment check cannot see a symlinked folder inside the library that
     * points elsewhere; the real path of the nearest folder that already exists can. Checked before
     * any folder is created, so nothing is ever made on the far side of such a link.
     */
    private void requireInsideRoot(Path parent) throws IOException {
        Path existing = parent;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Target folder " + parent + " resolves outside " + root);
        }
    }

    @Override
    public void abort(Staged staged) throws IOException {
        Files.deleteIfExists(partFile(staged));
    }

    @Override
    public void abortSession(UUID sessionId) throws IOException {
        Path dir = sessionDir(sessionId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Override
    public OptionalLong usableSpace() {
        try {
            return OptionalLong.of(Files.getFileStore(root).getUsableSpace());
        } catch (IOException _) {
            return OptionalLong.empty();
        }
    }

    @Override
    public boolean writable() {
        return Files.isDirectory(root) && Files.isWritable(root);
    }
}
