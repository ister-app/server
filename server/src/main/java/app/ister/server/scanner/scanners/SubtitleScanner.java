package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.OtherPathFileEntity;
import app.ister.server.enums.EventType;
import app.ister.server.enums.PathFileType;
import app.ister.server.events.subtitlefilefound.SubtitleFileFoundData;
import app.ister.server.repository.OtherPathFileRepository;
import app.ister.server.scanner.PathObject;
import app.ister.server.scanner.enums.DirType;
import app.ister.server.scanner.enums.FileType;
import app.ister.server.service.MessageSender;
import app.ister.server.service.ScannerHelperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SubtitleScanner implements Scanner {
    @Autowired
    private ScannerHelperService scannerHelperService;
    @Autowired
    private OtherPathFileRepository otherPathFileRepository;
    @Autowired
    private MessageSender messageSender;

    @Override
    public boolean analyzable(Path path, BasicFileAttributes attrs) {
        return attrs.isRegularFile()
                && new PathObject(path.toString()).getDirType().equals(DirType.EPISODE)
                && new PathObject(path.toString()).getFileType().equals(FileType.SUBTITLE);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, BasicFileAttributes attrs) {
        Optional<OtherPathFileEntity> otherPathFileEntity = otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString());
        if (otherPathFileEntity.isEmpty()) {
            var entity = OtherPathFileEntity.builder()
                    .directoryEntity(directoryEntity)
                    .pathFileType(PathFileType.SUBTITLE)
                    .path(path.toString()).build();
            otherPathFileRepository.save(entity);
            messageSender.sendSubtitleFileFound(SubtitleFileFoundData.builder()
                    .directoryEntityUUID(directoryEntity.getId())
                    .eventType(EventType.SUBTITLE_FILE_FOUND)
                    .path(path.toString()).build());
            return Optional.of(entity);
        } else {
            return Optional.of(otherPathFileEntity.get());
        }
    }
}
