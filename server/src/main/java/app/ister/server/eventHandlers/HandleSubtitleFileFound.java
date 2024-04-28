package app.ister.server.eventHandlers;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.EventType;
import app.ister.server.enums.StreamCodecType;
import app.ister.server.eventHandlers.subtitleFileFound.SubtitleFilePathParser;
import app.ister.server.repository.MediaFileStreamRepository;
import app.ister.server.scanner.PathObject;
import app.ister.server.scanner.enums.DirType;
import app.ister.server.service.ScannerHelperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static app.ister.server.eventHandlers.subtitleFileFound.SubtitleFilePathParser.mediaFileAndSubtitleFileBelongTogether;

@Component
public class HandleSubtitleFileFound implements Handle {
    @Autowired
    private ScannerHelperService scannerHelperService;
    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;

    @Override
    public EventType handles() {
        return EventType.SUBTITLE_FILE_FOUND;
    }

    @Override
    public Boolean handle(ServerEventEntity serverEventEntity) {
        analyze(serverEventEntity.getDirectoryEntity(), serverEventEntity.getPath());
        return true;
    }

    private void analyze(DirectoryEntity directoryEntity, String path) {
        PathObject pathObject = new PathObject(path);
        if (pathObject.getDirType().equals(DirType.EPISODE)) {
            analyzeSubtitleFile(directoryEntity, path, pathObject);
        }
    }


    private void analyzeSubtitleFile(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var episode = scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getShow(), pathObject.getShowYear(), pathObject.getSeason(), pathObject.getEpisode());
        episode.getMediaFileEntities().forEach(mediaFileEntity -> {
            if (directoryEntity.getId().equals(mediaFileEntity.getDirectoryEntity().getId()) && mediaFileAndSubtitleFileBelongTogether(mediaFileEntity.getPath(), path)) {
                var mediaFileStream = MediaFileStreamEntity.builder()
                        .mediaFileEntity(mediaFileEntity)
                        .codecName("subtitle srt")
                        .codecType(StreamCodecType.EXTERNAL_SUBTITLE)
                        .language(SubtitleFilePathParser.langCodeToIso3(path))
                        .path(path);
                mediaFileStreamRepository.save(mediaFileStream.build());
            }
        });
    }
}
