package app.ister.disk.scanner;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.service.MessageSender;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The filesystem walk of a LOCAL directory: a thin {@code java.nio} adapter over
 * {@link DirectoryPruner} and {@link ScanEntryDispatcher}.
 */
@Slf4j
class AnalyzerSimpleFileVisitor extends SimpleFileVisitor<Path> {
    private final DirectoryEntity directoryEntity;
    private final ScanEntryDispatcher dispatcher;

    public AnalyzerSimpleFileVisitor(DirectoryEntity directoryEntity,
                                     ScannedCache scannedCache,
                                     MessageSender messageSender,
                                     Scanners scanners) {
        this.directoryEntity = directoryEntity;
        this.dispatcher = new ScanEntryDispatcher(directoryEntity, scannedCache, messageSender, scanners);
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        String dirPath = dir.toString();
        if (dirPath.equals(directoryEntity.getPath())) {
            return FileVisitResult.CONTINUE;
        }
        if (dir.getFileName() != null && dir.getFileName().toString().startsWith(".")) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        if (!attrs.isDirectory()) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        return DirectoryPruner.shouldDescend(directoryEntity, dirPath)
                ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) {
        dispatcher.dispatch(new ScanEntry(path.toString(), basicFileAttributes.isRegularFile(),
                basicFileAttributes.size(),
                basicFileAttributes.lastModifiedTime() == null ? null : basicFileAttributes.lastModifiedTime().toInstant()));
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }
}
