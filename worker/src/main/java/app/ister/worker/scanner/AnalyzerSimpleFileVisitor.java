package app.ister.worker.scanner;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.service.MessageSender;
import app.ister.worker.scanner.enums.DirType;
import app.ister.worker.scanner.scanners.*;
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
    private final MediaFileScanner mediaFileScanner;
    private final ImageScanner imageScanner;
    private final NfoScanner nfoScanner;
    private final SubtitleScanner subtitleScanner;

    public AnalyzerSimpleFileVisitor(DirectoryEntity directoryEntity,
                                     ScannedCache scannedCache,
                                     MessageSender messageSender,
                                     MediaFileScanner mediaFileScanner,
                                     ImageScanner imageScanner,
                                     NfoScanner nfoScanner,
                                     SubtitleScanner subtitleScanner) {
        this.directoryEntity = directoryEntity;
        this.scannedCache = scannedCache;
        this.messageSender = messageSender;
        this.mediaFileScanner = mediaFileScanner;
        this.imageScanner = imageScanner;
        this.nfoScanner = nfoScanner;
        this.subtitleScanner = subtitleScanner;
    }

    /**
     * Called before visiting a dir.
     * - If it's the root dir continue the scan in that dir.
     * - If it's a dot dir (ex: .config) don't scan it.
     * - Else check if it's correct media dir.
     */
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if (dir.toString().equals(directoryEntity.getPath())) {
            // Root dir
            return FileVisitResult.CONTINUE;
        } else if (dir.getFileName().toString().startsWith(".")) {
            // Dot dir
            return FileVisitResult.SKIP_SUBTREE;
        } else {
            // Other dirs
            return analyzeDir(dir, attrs);
        }
    }

    /**
     * Check if the path is part of Show or Season.
     */
    private FileVisitResult analyzeDir(Path dir, BasicFileAttributes attrs) {
        if (attrs.isDirectory() && List.of(DirType.SHOW, DirType.SEASON).contains(new PathObject(dir.toString()).getDirType())) {
            return FileVisitResult.CONTINUE;
        } else {
            return FileVisitResult.SKIP_SUBTREE;
        }
    }

    @Override
    public FileVisitResult postVisitDirectory(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) {
        for (Scanner scanner : List.of(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner)) {
            // Check if scan is analyzable by scanner and is not analyzed before.
            if (scanner.analyzable(path, basicFileAttributes.isRegularFile(), basicFileAttributes.size()) && !scannedCache.foundPath(path.toString())) {
                log.debug("Found file: {}, for scanner: {}", path, scanner);
                messageSender.sendFileScanRequested(FileScanRequestedData.builder()
                        .path(path)
                        .regularFile(basicFileAttributes.isRegularFile())
                        .size(basicFileAttributes.size())
                        .directoryEntityUUID(directoryEntity.getId())
                        .eventType(EventType.FILE_SCAN_REQUESTED)
                        .build());
            }
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }
}
