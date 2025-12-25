package app.ister.worker.scanner.scanners;

import app.ister.core.entitiy.BaseEntity;
import app.ister.core.entitiy.DirectoryEntity;

import java.nio.file.Path;
import java.util.Optional;

public interface Scanner {
    boolean analyzable(Path dir, Boolean isRegularFile, long size);

    Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path dir, Boolean isRegularFile, long size);
}
