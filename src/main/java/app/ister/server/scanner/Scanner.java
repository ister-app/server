package app.ister.server.scanner;

import app.ister.server.entitiy.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;

@Component
@Slf4j
public class Scanner {
    @Autowired
    private ShowAnalyzer showAnalyzer;
    @Autowired
    private SeasonAnalyzer seasonAnalyzer;
    @Autowired
    private EpisodeAnalyzer episodeAnalyzer;
    @Autowired
    private MediaFileAnalyzer mediaFileAnalyzer;
    @Autowired
    private ImageAnalyzer imageAnalyzer;

    public void scanDiskForCategorie(DiskEntity diskEntity) throws IOException {
        ArrayList<MediaFileEntity> result = new ArrayList<>();
        scanDiskForCategorie(Path.of(diskEntity.getPath()), diskEntity);
    }

    public void scanDiskForCategorie(Path path, DiskEntity diskEntity) throws IOException {
        log.debug("Log: {}", path);
        Files.walkFileTree(path, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new AnalyzerSimpleFileVisitor(diskEntity, showAnalyzer, seasonAnalyzer, episodeAnalyzer, imageAnalyzer));
    }
}
