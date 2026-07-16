package app.ister.disk.scanner;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.service.MessageSender;
import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.scanners.AudioScanner;
import app.ister.disk.scanner.scanners.ComicScanner;
import app.ister.disk.scanner.scanners.EpubScanner;
import app.ister.disk.scanner.scanners.ImageScanner;
import app.ister.disk.scanner.scanners.NfoScanner;
import app.ister.disk.scanner.scanners.Scanner;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

@Slf4j
class AnalyzerSimpleFileVisitor extends SimpleFileVisitor<Path> {
    private final DirectoryEntity directoryEntity;
    private final ScannedCache scannedCache;
    private final MessageSender messageSender;
    private final Scanners scanners;

    public AnalyzerSimpleFileVisitor(DirectoryEntity directoryEntity,
                                     ScannedCache scannedCache,
                                     MessageSender messageSender,
                                     Scanners scanners) {
        this.directoryEntity = directoryEntity;
        this.scannedCache = scannedCache;
        this.messageSender = messageSender;
        this.scanners = scanners;
    }

    private String getDirectoryName() {
        return directoryEntity.getName();
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if (dir.toString().equals(directoryEntity.getPath())) {
            return FileVisitResult.CONTINUE;
        } else if (dir.getFileName().toString().startsWith(".")) {
            return FileVisitResult.SKIP_SUBTREE;
        } else {
            return analyzeDir(dir, attrs);
        }
    }

    private FileVisitResult analyzeDir(Path dir, BasicFileAttributes attrs) {
        if (!attrs.isDirectory()) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        if (libraryTypeIs(LibraryType.MUSIC)) {
            MusicPathObject musicPath = new MusicPathObject(directoryEntity.getPath(), dir.toString());
            return List.of(DirType.ARTIST, DirType.ALBUM).contains(musicPath.getDirType())
                    ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
        }
        if (libraryTypeIs(LibraryType.BOOK)) {
            BookPathObject bookPath = new BookPathObject(directoryEntity.getPath(), dir.toString());
            return List.of(DirType.ARTIST, DirType.ALBUM).contains(bookPath.getDirType())
                    ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
        }
        if (libraryTypeIs(LibraryType.COMIC)) {
            ComicPathObject comicPath = new ComicPathObject(directoryEntity.getPath(), dir.toString());
            return comicPath.getDirType() == DirType.SERIES
                    ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
        }
        return List.of(DirType.SHOW, DirType.SEASON).contains(new PathObject(dir.toString()).getDirType())
                ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) {
        boolean isMusic = libraryTypeIs(LibraryType.MUSIC);
        boolean isBook = libraryTypeIs(LibraryType.BOOK);
        boolean isComic = libraryTypeIs(LibraryType.COMIC);
        boolean directoryScoped = isMusic || isBook || isComic;
        List<Scanner> scannerList;
        if (isMusic) {
            scannerList = List.of(scanners.audio(), scanners.image(), scanners.nfo());
        } else if (isBook) {
            scannerList = List.of(scanners.epub(), scanners.audio(), scanners.image(), scanners.nfo());
        } else if (isComic) {
            scannerList = List.of(scanners.comic(), scanners.image());
        } else {
            scannerList = List.of(scanners.mediaFile(), scanners.image(), scanners.nfo(), scanners.subtitle());
        }
        for (Scanner scanner : scannerList) {
            boolean canAnalyze = directoryScoped
                    ? directoryScopedAnalyzable(scanner, path, basicFileAttributes)
                    : scanner.analyzable(path, basicFileAttributes.isRegularFile(), basicFileAttributes.size());
            boolean alreadyScanned = directoryScoped && scanner instanceof AudioScanner
                    ? scannedCache.foundMusicAudioPath(path.toString())
                    : scannedCache.foundPath(path.toString());
            if (canAnalyze && !alreadyScanned) {
                log.debug("Found file: {}, for scanner: {}", path, scanner);
                messageSender.sendFileScanRequested(FileScanRequestedData.builder()
                        .path(path)
                        .regularFile(basicFileAttributes.isRegularFile())
                        .size(basicFileAttributes.size())
                        .directoryEntityUUID(directoryEntity.getId())
                        .eventType(EventType.FILE_SCAN_REQUESTED)
                        .build(), getDirectoryName());
            }
        }
        return FileVisitResult.CONTINUE;
    }

    /** Music and book libraries use the directory-aware analyzable overloads (path parsing needs the library root). */
    private boolean directoryScopedAnalyzable(Scanner scanner, Path path, BasicFileAttributes attrs) {
        boolean regular = attrs.isRegularFile();
        long size = attrs.size();
        if (scanner instanceof AudioScanner s) {
            return s.analyzable(path, regular, directoryEntity);
        }
        if (scanner instanceof EpubScanner s) {
            return s.analyzable(path, regular, directoryEntity);
        }
        if (scanner instanceof ComicScanner s) {
            return s.analyzable(path, regular, directoryEntity);
        }
        if (scanner instanceof ImageScanner s) {
            return s.analyzable(path, regular, size, directoryEntity);
        }
        if (scanner instanceof NfoScanner s) {
            return s.analyzable(path, regular, size, directoryEntity);
        }
        return scanner.analyzable(path, regular, size);
    }

    private boolean libraryTypeIs(LibraryType libraryType) {
        return directoryEntity.getLibraryEntity() != null
                && directoryEntity.getLibraryEntity().getLibraryType() == libraryType;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }
}
