package app.ister.disk.scanner.scanners;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.OtherPathFileEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.PathFileType;
import app.ister.core.eventdata.SubtitleFileFoundData;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.disk.scanner.PathObject;
import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Optional;

@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class SubtitleScanner implements Scanner {
    private final OtherPathFileRepository otherPathFileRepository;
    private final MessageSender messageSender;

    @Override
    public boolean analyzable(Path path, Boolean isRegularFile, long size) {
        return isRegularFile
                && new PathObject(path.toString()).getDirType().equals(DirType.EPISODE)
                && new PathObject(path.toString()).getFileType().equals(FileType.SUBTITLE);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, Boolean isRegularFile, long size) {
        Optional<OtherPathFileEntity> otherPathFileEntity = otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString());
        if (otherPathFileEntity.isEmpty()) {
            var entity = OtherPathFileEntity.builder()
                    .directoryEntityId(directoryEntity.getId())
                    .pathFileType(PathFileType.SUBTITLE)
                    .path(path.toString()).build();
            otherPathFileRepository.save(entity);
            messageSender.sendSubtitleFileFound(SubtitleFileFoundData.builder()
                    .directoryEntityUUID(directoryEntity.getId())
                    .eventType(EventType.SUBTITLE_FILE_FOUND)
                    .path(path.toString()).build(), directoryEntity.getName());
            return Optional.of(entity);
        } else {
            return Optional.of(otherPathFileEntity.get());
        }
    }
}
