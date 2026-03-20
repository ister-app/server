package app.ister.disk.scanner;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.disk.scanner.scanners.ImageScanner;
import app.ister.disk.scanner.scanners.MediaFileScanner;
import app.ister.disk.scanner.scanners.NfoScanner;
import app.ister.disk.scanner.scanners.SubtitleScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

@Component
@Slf4j
@RequiredArgsConstructor
public class LibraryScanner {
    private final MessageSender messageSender;
    private final MediaFileScanner mediaFileScanner;
    private final ImageScanner imageScanner;
    private final NfoScanner nfoScanner;
    private final SubtitleScanner subtitleScanner;
    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;
    private final OtherPathFileRepository otherPathFileRepository;

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
