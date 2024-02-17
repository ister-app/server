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
//    Optional<TVShow> inShow;
//    Optional<Season> inSeason;

    private final ShowAnalyzer showAnalyzer;
    private final SeasonAnalyzer seasonAnalyzer;
    private final EpisodeAnalyzer episodeAnalyzer;
    private final ImageAnalyzer imageAnalyzer;

    public AnalyzerSimpleFileVisitor(DiskEntity diskEntity, ShowAnalyzer showAnalyzer, SeasonAnalyzer seasonAnalyzer, EpisodeAnalyzer episodeAnalyzer, ImageAnalyzer imageAnalyzer) {
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
        for (Analyzer analyzer : List.of(showAnalyzer, seasonAnalyzer)) {
            if (analyzer.analyzable(dir, attrs, analyzeStack)) {
                log.debug("Scanning dir: {}, with scanner: {}", dir, analyzer);
                analyzeStack.push(analyzer.analyze(diskEntity, dir, attrs, analyzeStack).orElseThrow());
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
        for (Analyzer analyzer : List.of(episodeAnalyzer, imageAnalyzer)) {
            if (analyzer.analyzable(file, attrs, analyzeStack)) {
                log.debug("Scanning file: {}, with scanner: {}", file, analyzer);
                analyzer.analyze(diskEntity, file, attrs, analyzeStack);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }
}
