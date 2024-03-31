package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DirectoryEntity;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

public interface Scanner {
    boolean analyzable(Path dir, BasicFileAttributes attrs);

    Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path dir, BasicFileAttributes attrs);
}
