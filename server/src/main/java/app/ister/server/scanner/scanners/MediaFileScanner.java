package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.EventType;
import app.ister.server.repository.MediaFileRepository;
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
public class MediaFileScanner implements Scanner {
    @Autowired
    private ScannerHelperService scannerHelperService;
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private ServerEventRepository serverEventRepository;

    @Override
    public boolean analyzable(Path path, BasicFileAttributes attrs) {
        return attrs.isRegularFile()
                && new PathObject(path.toString()).getDirType().equals(DirType.EPISODE)
                && new PathObject(path.toString()).getFileType().equals(FileType.MEDIA);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, BasicFileAttributes attrs) {
        PathObject pathObject = new PathObject(path.toString());
        EpisodeEntity episodeEntity = scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getShow(), pathObject.getShowYear(), pathObject.getSeason(), pathObject.getEpisode());
        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndEpisodeEntityAndPath(directoryEntity, episodeEntity, path.toString());
        if (mediaFile.isEmpty()) {
            MediaFileEntity entity = MediaFileEntity.builder()
                    .directoryEntity(directoryEntity)
                    .episodeEntity(episodeEntity)
                    .path(path.toString())
                    .size(attrs.size()).build();
            mediaFileRepository.save(entity);
            serverEventRepository.save(ServerEventEntity.builder()
                    .eventType(EventType.MEDIA_FILE_FOUND)
                    .directoryEntity(directoryEntity)
                    .episodeEntity(episodeEntity)
                    .path(path.toString()).build());
        }
        return Optional.ofNullable(episodeEntity);
    }
}
