package app.ister.worker.scanner;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.worker.scanner.scanners.ImageScanner;
import app.ister.worker.scanner.scanners.MediaFileScanner;
import app.ister.worker.scanner.scanners.NfoScanner;
import app.ister.worker.scanner.scanners.SubtitleScanner;
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
    private MessageSender messageSender;
    @Autowired
    private MediaFileScanner mediaFileScanner;
    @Autowired
    private ImageScanner imageScanner;
    @Autowired
    private NfoScanner nfoScanner;
    @Autowired
    private SubtitleScanner subtitleScanner;
    @Autowired
    private ImageRepository imageRepository;
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private OtherPathFileRepository otherPathFileRepository;


    public void scanDirectory(DirectoryEntity directoryEntity) throws IOException {
        scanDirectory(Path.of(directoryEntity.getPath()), directoryEntity);
    }

    public void scanDirectory(Path path, DirectoryEntity directoryEntity) throws IOException {
        log.debug("Log: {}", path);
        ScannedCache scannedCache = new ScannedCache(directoryEntity, imageRepository, mediaFileRepository, otherPathFileRepository);
        Files.walkFileTree(path, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new AnalyzerSimpleFileVisitor(directoryEntity, scannedCache, messageSender, mediaFileScanner, imageScanner, nfoScanner, subtitleScanner));
        scannedCache.removeNotScannedFilesFromDatabase();
    }
}
