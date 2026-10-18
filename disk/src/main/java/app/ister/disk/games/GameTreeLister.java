package app.ister.disk.games;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.storage.ObjectRef;
import app.ister.core.storage.ObjectStat;
import app.ister.core.storage.ObjectStore;
import app.ister.core.storage.ObjectStoreRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lists the files of a folder-form game (PS3, Steam) from its game folder, for LOCAL and S3
 * directories alike. Folder games have no per-file rows: this listing is what the scanner sizes
 * the game with and what the game manifest endpoint hands to clients. Dot-files are skipped
 * (the upload staging dir, OS junk); symlinks are not followed.
 */
@Component
@RequiredArgsConstructor
public class GameTreeLister {

    private final ObjectStoreRegistry objectStoreRegistry;

    /** One file of the tree: path relative to the game folder ('/'-separated), its size and mtime. */
    public record TreeFile(String relativePath, long size, Instant lastModified) {}

    public record TreeStats(long totalBytes, int fileCount) {}

    /** Every regular file under {@code rootPath}, sorted by relative path. */
    public List<TreeFile> list(DirectoryEntity directory, String rootPath) throws IOException {
        if (directory.isS3()) {
            return listS3(directory, rootPath);
        }
        Path root = Path.of(rootPath);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> !hasDotSegment(root.relativize(path)))
                    .map(path -> toTreeFile(root, path))
                    .sorted(java.util.Comparator.comparing(TreeFile::relativePath))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public TreeStats stats(DirectoryEntity directory, String rootPath) throws IOException {
        long bytes = 0;
        int count = 0;
        for (TreeFile file : list(directory, rootPath)) {
            bytes += file.size();
            count++;
        }
        return new TreeStats(bytes, count);
    }

    /** Whether {@code rootPath/relative} names a directory with at least one file (LOCAL: any directory). */
    public boolean isNonEmptyDirectory(DirectoryEntity directory, String rootPath, String relative) {
        if (directory.isS3()) {
            ObjectStore store = objectStoreRegistry.forDirectory(directory);
            String prefix = ObjectRef.parse(rootPath).key() + "/" + relative + "/";
            ObjectStore.Listing listing = store.listShallow(prefix);
            return !listing.objects().isEmpty() || !listing.childPrefixes().isEmpty();
        }
        return Files.isDirectory(Path.of(rootPath, relative));
    }

    private List<TreeFile> listS3(DirectoryEntity directory, String rootPath) {
        ObjectStore store = objectStoreRegistry.forDirectory(directory);
        String prefix = ObjectRef.parse(rootPath).key() + "/";
        try (Stream<ObjectStat> objects = store.list(prefix)) {
            return objects
                    .filter(object -> object.key().length() > prefix.length())
                    .map(object -> new TreeFile(object.key().substring(prefix.length()), object.size(), object.lastModified()))
                    .filter(file -> !hasDotSegment(file.relativePath()))
                    .sorted(java.util.Comparator.comparing(TreeFile::relativePath))
                    .toList();
        }
    }

    private static TreeFile toTreeFile(Path root, Path path) {
        try {
            var attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
            return new TreeFile(relative, attrs.size(), attrs.lastModifiedTime().toInstant());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean hasDotSegment(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDotSegment(String relative) {
        for (String segment : relative.split("/")) {
            if (segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }
}
