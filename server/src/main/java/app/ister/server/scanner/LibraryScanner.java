package app.ister.server.scanner;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.scanner.scanners.ImageScanner;
import app.ister.server.scanner.scanners.MediaFileScanner;
import app.ister.server.scanner.scanners.NfoScanner;
import app.ister.server.scanner.scanners.SubtitleScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

@Component
@Slf4j
public class LibraryScanner {
    @Autowired
    private MediaFileScanner mediaFileScanner;
    @Autowired
    private ImageScanner imageScanner;
    @Autowired
    private NfoScanner nfoScanner;
    @Autowired
    private SubtitleScanner subtitleScanner;

    public void scanDirectory(DirectoryEntity directoryEntity) throws IOException {
        scanDirectory(Path.of(directoryEntity.getPath()), directoryEntity);
    }

    public void scanDirectory(Path path, DirectoryEntity directoryEntity) throws IOException {
        log.debug("Log: {}", path);
        Files.walkFileTree(path, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new AnalyzerSimpleFileVisitor(directoryEntity, mediaFileScanner, imageScanner, nfoScanner, subtitleScanner));
    }
}
