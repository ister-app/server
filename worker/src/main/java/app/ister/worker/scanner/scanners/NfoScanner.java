package app.ister.worker.scanner.scanners;

import app.ister.core.entitiy.BaseEntity;
import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.OtherPathFileEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.PathFileType;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.worker.scanner.PathObject;
import app.ister.worker.scanner.enums.DirType;
import app.ister.worker.scanner.enums.FileType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Optional;

@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class NfoScanner implements Scanner {
    @Autowired
    private OtherPathFileRepository otherPathFileRepository;
    @Autowired
    private MessageSender messageSender;

    @Override
    public boolean analyzable(Path path, Boolean isRegularFile, long size) {
        PathObject pathObject = new PathObject(path.toString());
        Boolean showCorrect = pathObject.getDirType().equals(DirType.SHOW) && path.getFileName().toString().equals("tvshow.nfo");
        Boolean episodeCorrect = pathObject.getDirType().equals(DirType.EPISODE) && pathObject.getFileType().equals(FileType.NFO);
        return (isRegularFile
                && pathObject.getFileType().equals(FileType.NFO)
                && (showCorrect ^ episodeCorrect));
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, Boolean isRegularFile, long size) {
        Optional<OtherPathFileEntity> otherPathFileEntity = otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString());
        if (otherPathFileEntity.isEmpty()) {
            var entity = OtherPathFileEntity.builder()
                    .directoryEntityId(directoryEntity.getId())
                    .pathFileType(PathFileType.NFO)
                    .path(path.toString()).build();
            otherPathFileRepository.save(entity);
            messageSender.sendNfoFileFound(NfoFileFoundData.builder()
                    .directoryEntityUUID(directoryEntity.getId())
                    .eventType(EventType.NFO_FILE_FOUND)
                    .path(path.toString()).build());
            return Optional.of(entity);
        } else {
            return Optional.of(otherPathFileEntity.get());
        }
    }
}
