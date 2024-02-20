package app.ister.server.scanner;

import app.ister.server.entitiy.*;
import app.ister.server.enums.EventType;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.ServerEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Optional;

@Component
@Slf4j
public class EpisodeScanner implements Scanner {
    final static String REGEX = "s(\\d{1,4})e(\\d{1,4}).mkv";

    @Autowired
    private EpisodeRepository episodeRepository;
    @Autowired
    private ServerEventRepository serverEventRepository;

    @Override
    public boolean analyzable(Path dir, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
        return (analyzeStack.peek() != null
                && analyzeStack.peek().getClass().equals(SeasonEntity.class))
                && attrs.isRegularFile()
                && getMatcher(REGEX, dir.getFileName().toString().toLowerCase()).matches();
    }

    @Override
    public Optional<BaseEntity> analyze(DiskEntity diskEntity, Path dir, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
        SeasonEntity inSeason = (SeasonEntity) analyzeStack.peek();
        ShowEntity inShow = (ShowEntity) analyzeStack.peekLast();
        return getMatcher(REGEX, dir.getFileName().toString().toLowerCase()).results().map(matchResult -> {
            var number = Integer.parseInt(matchResult.group(2));
            Optional<EpisodeEntity> episode = episodeRepository.findByShowEntityAndSeasonEntityAndNumber(inShow, inSeason, number);
            if (episode.isPresent()) {
                log.debug("Saving: " + episode.get().getNumber());
                return episode.get();
            } else {
                EpisodeEntity episodeEntity1 = new EpisodeEntity(inShow, inSeason, number);
                episodeRepository.save(episodeEntity1);
                serverEventRepository.save(new ServerEventEntity(EventType.MEDIA_FILE_FOUND, diskEntity, episodeEntity1, dir.toString()));
                return episodeEntity1;
            }
        }).findFirst().map(episodeEntity -> episodeEntity);
    }
}
