package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MovieEntity;
import app.ister.server.enums.EventType;
import app.ister.server.events.mediafilefound.MediaFileFoundData;
import app.ister.server.repository.MediaFileRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class MediaFileScanner implements Scanner {
    @Autowired
    private ScannerHelperService scannerHelperService;
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private MessageSender messageSender;

    @Override
    public boolean analyzable(Path path, Boolean isRegularFile, long size) {
        PathObject pathObject = new PathObject(path.toString());
        return isRegularFile
                && List.of(DirType.EPISODE, DirType.MOVIE).contains(pathObject.getDirType())
                && pathObject.getFileType().equals(FileType.MEDIA);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, Boolean isRegularFile, long size) {
        PathObject pathObject = new PathObject(path.toString());
        Optional<EpisodeEntity> episodeEntity = Optional.empty();
        Optional<MovieEntity> movieEntity = Optional.empty();
        UUID episodeId = null;
        UUID movieId = null;
        switch (pathObject.getDirType()) {
            case EPISODE -> {
                episodeEntity = Optional.of(scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason(), pathObject.getEpisode()));
                episodeId = episodeEntity.get().getId();
            }
            case MOVIE -> {
                movieEntity = Optional.of(scannerHelperService.getOrCreateMovie(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear()));
                movieId = movieEntity.get().getId();
            }
            default -> {
                throw new IllegalStateException("Only EPISODE or MOVIE is supported");
            }
        }

        Optional<MediaFileEntity> mediaFile = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString());
        if (mediaFile.isEmpty()) {
            MediaFileEntity entity = MediaFileEntity.builder()
                    .directoryEntity(directoryEntity)
                    .episodeEntity(episodeEntity.orElse(null))
                    .movieEntity(movieEntity.orElse(null))
                    .path(path.toString())
                    .size(size).build();
            mediaFileRepository.save(entity);
            messageSender.sendMediaFileFound(MediaFileFoundData.builder()
                    .eventType(EventType.MEDIA_FILE_FOUND)
                    .directoryEntityUUID(directoryEntity.getId())
                    .episodeEntityUUID(episodeId)
                    .movieEntityUUID(movieId)
                    .path(path.toString()).build());
        }
        return Optional.ofNullable(episodeEntity.orElse(null));
    }
}
