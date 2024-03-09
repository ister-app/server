package app.ister.server.eventHandlers;

import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.entitiy.ServerEventEntity;
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
    public Boolean handle(ServerEventEntity serverEventEntity) {
        analyze(serverEventEntity.getDiskEntity(), serverEventEntity.getPath());
        return true;
    }

    private void analyze(DiskEntity diskEntity, String path) {
        PathObject pathObject = new PathObject(path);
        if (pathObject.getDirType().equals(DirType.EPISODE)) {
            analyzeEpisode(diskEntity, path, pathObject);
        }
    }


    private void analyzeEpisode(DiskEntity diskEntity, String path, PathObject pathObject) {
        var episode = scannerHelperService.getOrCreateEpisode(diskEntity.getCategorieEntity(), pathObject.getShow(), pathObject.getShowYear(), pathObject.getSeason(), pathObject.getEpisode());
        episode.getMediaFileEntities().forEach(mediaFileEntity -> {
            if (diskEntity.getId().equals(mediaFileEntity.getDiskEntity().getId()) && mediaFileAndSubtitleFileBelongTogether(mediaFileEntity.getPath(), path)) {
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
