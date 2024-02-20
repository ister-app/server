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

    ArrayDeque<BaseEntity> analyzeStack = new ArrayDeque<>();

    private final ShowScanner showAnalyzer;
    private final SeasonScanner seasonAnalyzer;
    private final EpisodeScanner episodeAnalyzer;
    private final ImageScanner imageAnalyzer;

    public AnalyzerSimpleFileVisitor(DiskEntity diskEntity, ShowScanner showAnalyzer, SeasonScanner seasonAnalyzer, EpisodeScanner episodeAnalyzer, ImageScanner imageAnalyzer) {
        this.diskEntity = diskEntity;
        this.showAnalyzer = showAnalyzer;
        this.seasonAnalyzer = seasonAnalyzer;
        this.episodeAnalyzer = episodeAnalyzer;
        this.imageAnalyzer = imageAnalyzer;
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
            if (scanner.analyzable(dir, attrs, analyzeStack)) {
                log.debug("Scanning dir: {}, with scanner: {}", dir, scanner);
                analyzeStack.push(scanner.analyze(diskEntity, dir, attrs, analyzeStack).orElseThrow());
                return FileVisitResult.CONTINUE;
            }
        }
        return FileVisitResult.SKIP_SUBTREE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path file, IOException exception) {
        if (!analyzeStack.isEmpty()) {
            analyzeStack.pop();
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        for (Scanner scanner : List.of(episodeAnalyzer, imageAnalyzer)) {
            if (scanner.analyzable(file, attrs, analyzeStack)) {
                log.debug("Scanning file: {}, with scanner: {}", file, scanner);
                scanner.analyze(diskEntity, file, attrs, analyzeStack);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }
}
