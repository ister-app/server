package app.ister.server.scanner;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.repository.ShowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class ShowScanner implements Scanner {

    String regex = "(.*)\\((\\d{4})\\)*";

    @Autowired
    private ShowRepository showRepository;

    @Override
    public boolean analyzable(Path dir, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
        return analyzeStack != null
                && analyzeStack.isEmpty()
                && attrs.isDirectory()
                && Pattern.compile(regex, Pattern.DOTALL).matcher(dir.getFileName().toString()).matches();
    }

    @Override
    public Optional<BaseEntity> analyze(DiskEntity diskEntity, Path dir, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
//        return Optional.empty();
//    }
//
//    public Optional<ShowEntity> get(CategorieEntity categorieEntity, String fileName) {
        final Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        final Matcher matcher = pattern.matcher(dir.getFileName().toString());
        return matcher.results().map(matchResult -> {
            String name = matchResult.group(1).trim();
            int releaseYear = Integer.parseInt(matchResult.group(2));
            Optional<ShowEntity> show = showRepository.findByNameAndReleaseYear(name, releaseYear);
            if (show.isPresent()) {
                log.debug("Saving: " + show.get().getName());
                return show.get();
            } else {
                ShowEntity showEntity = ShowEntity.builder()
                        .categorieEntity(diskEntity.getCategorieEntity())
                        .name(name)
                        .releaseYear(releaseYear).build();
                showRepository.save(showEntity);
                return showEntity;
            }
        }).findFirst().map(showEntity -> showEntity);
    }
}
