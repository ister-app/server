package app.ister.disk.scanner.scanners;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.DirectoryEntity;

import java.util.Optional;

public interface Scanner {
    boolean analyzable(String dir, boolean isRegularFile, long size);

    Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, String dir, boolean isRegularFile, long size);
}
