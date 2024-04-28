package app.ister.server.eventHandlers;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.EventType;
import app.ister.server.nfo.Parser;
import app.ister.server.repository.MetadataRepository;
import app.ister.server.scanner.PathObject;
import app.ister.server.scanner.enums.DirType;
import app.ister.server.service.ScannerHelperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;

@Component
@Slf4j
public class HandleNfoFileFound implements Handle {
    @Autowired
    private MetadataRepository metadataRepository;
    @Autowired
    private ScannerHelperService scannerHelperService;

    @Override
    public EventType handles() {
        return EventType.NFO_FILE_FOUND;
    }

    @Override
    public Boolean handle(ServerEventEntity serverEventEntity) {
        analyze(serverEventEntity.getDirectoryEntity(), serverEventEntity.getPath());
        return true;
    }

    public void analyze(DirectoryEntity directoryEntity, String path) {
        PathObject pathObject = new PathObject(path);
        if (pathObject.getDirType().equals(DirType.SHOW)) {
            analyzeShow(directoryEntity, path, pathObject);
        } else if (pathObject.getDirType().equals(DirType.EPISODE)) {
            analyzeEpisode(directoryEntity, path, pathObject);
        }
    }

    private void analyzeShow(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var show = scannerHelperService.getOrCreateShow(directoryEntity.getLibraryEntity(), pathObject.getShow(), pathObject.getShowYear());
        try {
            var parsed = Parser.parseShow(path).orElseThrow();
            metadataRepository.save(MetadataEntity.builder()
                    .title(parsed.getTitle())
                    .description(parsed.getPlot())
                    .released(parsed.getPremiered())
                    .showEntity(show).build());
        } catch (FileNotFoundException e) {
            log.error("Something went wrong when nfo parsing: {}", path);
        }
    }

    private void analyzeEpisode(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var episode = scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getShow(), pathObject.getShowYear(), pathObject.getSeason(), pathObject.getEpisode());
        try {
            var parsed = Parser.parseEpisode(path).orElseThrow();
            metadataRepository.save(MetadataEntity.builder()
                    .title(parsed.getTitle())
                    .description(parsed.getPlot())
                    .released(parsed.getAired())
                    .episodeEntity(episode)
                    .sourceUri("file://" + path).build());
        } catch (FileNotFoundException e) {
            log.error("Something went wrong when nfo parsing: {}", path);
        }
    }
}
