package app.ister.disk.scanner;

import app.ister.core.enums.LibraryType;
import app.ister.core.storage.PathStrings;
import app.ister.disk.scanner.enums.DirType;

import java.util.List;

/**
 * Decides whether a scan descends into a directory: the library root always, dot-directories
 * never, and otherwise only the levels the library type's path layout knows about (show/season,
 * artist/album, author/book, series). Shared by the filesystem walk and the S3 listing so both
 * prune identically.
 */
final class DirectoryPruner {

    private DirectoryPruner() {
    }

    static boolean shouldDescend(app.ister.core.entity.DirectoryEntity directoryEntity, String dirPath) {
        String root = directoryEntity.getPath();
        if (dirPath.equals(root) || (root.endsWith("/") && dirPath.equals(root.substring(0, root.length() - 1)))) {
            return true;
        }
        String name = PathStrings.fileName(dirPath);
        if (name != null && name.startsWith(".")) {
            return false;
        }
        LibraryType type = directoryEntity.getLibraryEntity() == null ? null
                : directoryEntity.getLibraryEntity().getLibraryType();
        if (type == LibraryType.MUSIC) {
            MusicPathObject musicPath = new MusicPathObject(root, dirPath, true);
            return List.of(DirType.ARTIST, DirType.ALBUM).contains(musicPath.getDirType());
        }
        if (type == LibraryType.BOOK) {
            BookPathObject bookPath = new BookPathObject(root, dirPath, true);
            return List.of(DirType.ARTIST, DirType.ALBUM).contains(bookPath.getDirType());
        }
        if (type == LibraryType.COMIC) {
            ComicPathObject comicPath = new ComicPathObject(root, dirPath, true);
            return comicPath.getDirType() == DirType.SERIES;
        }
        return List.of(DirType.SHOW, DirType.SEASON).contains(new PathObject(dirPath).getDirType());
    }
}
