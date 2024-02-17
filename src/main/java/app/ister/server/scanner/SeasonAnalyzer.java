package app.ister.server.scanner;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.SeasonEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.repository.SeasonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@Slf4j
public class SeasonAnalyzer implements Analyzer {
    final static String REGEX = "season\\s+(\\d{1,4})";

    @Autowired
    private SeasonRepository seasonRepository;

    @Override
    public boolean analyzable(Path dir, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
        return (analyzeStack.peek() != null
                && analyzeStack.peek().getClass().equals(ShowEntity.class))
                && attrs.isDirectory()
                && Pattern.compile(REGEX, Pattern.DOTALL).matcher(dir.getFileName().toString().toLowerCase()).matches();
    }

    @Override
    public Optional<BaseEntity> analyze(DiskEntity diskEntity, Path dir, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
        ShowEntity showEntity = (ShowEntity) analyzeStack.peek();
        return getMatcher(REGEX, dir.getFileName().toString().toLowerCase()).results().map(matchResult -> {
            var number = Integer.parseInt(matchResult.group(1));
            Optional<SeasonEntity> season = seasonRepository.findByShowEntityAndNumber(showEntity, number);
            if (season.isPresent()) {
                log.debug("Saving: " + season.get().getNumber());
                return season.get();
            } else {
                SeasonEntity seasonEntity1 = new SeasonEntity(showEntity, number);
                seasonRepository.save(seasonEntity1);
                return seasonEntity1;
            }
        }).findFirst().map(seasonEntity -> seasonEntity);
    }
}
