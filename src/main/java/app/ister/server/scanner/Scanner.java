package app.ister.server.scanner;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DiskEntity;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

public interface Scanner {
    boolean analyzable(Path dir, BasicFileAttributes attrs);

    Optional<BaseEntity> analyze(DiskEntity diskEntity, Path dir, BasicFileAttributes attrs);
}
