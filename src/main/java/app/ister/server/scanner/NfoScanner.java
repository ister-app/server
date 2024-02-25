package app.ister.server.scanner;

import app.ister.server.entitiy.*;
import app.ister.server.enums.EventType;
import app.ister.server.enums.ImageType;
import app.ister.server.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Optional;

@Component
@Slf4j
public class NfoScanner implements Scanner {
    final static String REGEX_EPISODE = "s(\\d{1,4})e(\\d{1,4}).nfo";
    final static String REGEX_SHOW = "tvshow.nfo";

    @Autowired
    private NfoRepository nfoRepository;
    @Autowired
    private ServerEventRepository serverEventRepository;

    @Override
    public boolean analyzable(Path path, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
        BaseEntity parent = analyzeStack.peek();
        if (parent != null && parent.getClass().equals(ShowEntity.class)) {
            return attrs.isRegularFile()
                    && getMatcher(REGEX_SHOW, path.getFileName().toString().toLowerCase()).matches();
        } else if (parent != null && parent.getClass().equals(SeasonEntity.class)) {
            return attrs.isRegularFile()
                    && getMatcher(REGEX_EPISODE, path.getFileName().toString().toLowerCase()).matches();
        } else {
            return false;
        }


    }

    @Override
    public Optional<BaseEntity> analyze(DiskEntity diskEntity, Path file, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
        Optional<NfoEntity> nfoEntity = nfoRepository.findByDiskEntityAndPath(diskEntity, file.toString());
        if (nfoEntity.isEmpty()) {
            var entity = NfoEntity.builder()
                    .diskEntity(diskEntity)
                    .path(file.toString()).build();
            nfoRepository.save(entity);
            serverEventRepository.save(ServerEventEntity.builder()
                    .diskEntity(diskEntity)
                    .eventType(EventType.NFO_FILE_FOUND)
                    .path(file.toString()).build());
            return Optional.of(entity);
        } else {
            return Optional.of(nfoEntity.get());
        }
    }
}
