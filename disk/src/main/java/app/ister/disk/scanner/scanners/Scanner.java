package app.ister.disk.scanner.scanners;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.DirectoryEntity;

import java.nio.file.Path;
import java.util.Optional;

public interface Scanner {
    boolean analyzable(Path dir, boolean isRegularFile, long size);

    Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path dir, boolean isRegularFile, long size);
}
