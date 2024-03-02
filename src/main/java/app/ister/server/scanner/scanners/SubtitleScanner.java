package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.*;
import app.ister.server.enums.EventType;
import app.ister.server.enums.PathFileType;
import app.ister.server.repository.OtherPathFileRepository;
import app.ister.server.repository.ServerEventRepository;
import app.ister.server.scanner.PathObject;
import app.ister.server.scanner.enums.DirType;
import app.ister.server.scanner.enums.FileType;
import app.ister.server.service.ScannerHelperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

@Component
@Slf4j
public class SubtitleScanner implements Scanner {
    @Autowired
    private ScannerHelperService scannerHelperService;
    @Autowired
    private OtherPathFileRepository otherPathFileRepository;
    @Autowired
    private ServerEventRepository serverEventRepository;

    @Override
    public boolean analyzable(Path path, BasicFileAttributes attrs) {
        return attrs.isRegularFile()
                && new PathObject(path.toString()).getDirType().equals(DirType.EPISODE)
                && new PathObject(path.toString()).getFileType().equals(FileType.SUBTITLE);
    }

    @Override
    public Optional<BaseEntity> analyze(DiskEntity diskEntity, Path path, BasicFileAttributes attrs) {
        Optional<OtherPathFileEntity> otherPathFileEntity = otherPathFileRepository.findByDiskEntityAndPath(diskEntity, path.toString());
        if (otherPathFileEntity.isEmpty()) {
            var entity = OtherPathFileEntity.builder()
                    .diskEntity(diskEntity)
                    .pathFileType(PathFileType.SUBTITLE)
                    .path(path.toString()).build();
            otherPathFileRepository.save(entity);
            serverEventRepository.save(ServerEventEntity.builder()
                    .diskEntity(diskEntity)
                    .eventType(EventType.SUBTITLE_FILE_FOUND)
                    .path(path.toString()).build());
            return Optional.of(entity);
        } else {
            return Optional.of(otherPathFileEntity.get());
        }
    }
}
