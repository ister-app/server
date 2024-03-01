package app.ister.server.scanner;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DiskEntity;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.List;

@Slf4j
class AnalyzerSimpleFileVisitor extends SimpleFileVisitor<Path> {
    private final DiskEntity diskEntity;

    private final ShowScanner showAnalyzer;
    private final SeasonScanner seasonAnalyzer;
    private final MediaFileScanner episodeAnalyzer;
    private final ImageScanner imageAnalyzer;
    private final NfoScanner nfoScanner;

    public AnalyzerSimpleFileVisitor(DiskEntity diskEntity, ShowScanner showAnalyzer, SeasonScanner seasonAnalyzer, MediaFileScanner episodeAnalyzer, ImageScanner imageAnalyzer, NfoScanner nfoScanner) {
        this.diskEntity = diskEntity;
        this.showAnalyzer = showAnalyzer;
        this.seasonAnalyzer = seasonAnalyzer;
        this.episodeAnalyzer = episodeAnalyzer;
        this.imageAnalyzer = imageAnalyzer;
        this.nfoScanner = nfoScanner;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if (dir.toString().equals(diskEntity.getPath())) {
            // Root dir
            return FileVisitResult.CONTINUE;
        } else if (dir.getFileName().toString().startsWith(".")) {
            // Dot dir
            return FileVisitResult.SKIP_SUBTREE;
        } else {
            return analyzeDir(dir, attrs);
        }
    }

    private FileVisitResult analyzeDir(Path dir, BasicFileAttributes attrs) {
        for (Scanner scanner : List.of(showAnalyzer, seasonAnalyzer)) {
            if (scanner.analyzable(dir, attrs)) {
                log.debug("Scanning dir: {}, with scanner: {}", dir, scanner);
                scanner.analyze(diskEntity, dir, attrs).orElseThrow();
                return FileVisitResult.CONTINUE;
            }
        }
        return FileVisitResult.SKIP_SUBTREE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        for (Scanner scanner : List.of(episodeAnalyzer, imageAnalyzer, nfoScanner)) {
            if (scanner.analyzable(file, attrs)) {
                log.debug("Scanning file: {}, with scanner: {}", file, scanner);
                scanner.analyze(diskEntity, file, attrs);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }
}
