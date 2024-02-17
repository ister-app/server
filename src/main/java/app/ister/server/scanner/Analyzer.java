package app.ister.server.scanner;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DiskEntity;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface Analyzer {

    default Matcher getMatcher(String regex, String fileName) {
        return Pattern.compile(regex, Pattern.DOTALL).matcher(fileName.toLowerCase());
    }

    boolean analyzable(Path dir, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack);

    Optional<BaseEntity> analyze(DiskEntity diskEntity, Path dir, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack);
}
